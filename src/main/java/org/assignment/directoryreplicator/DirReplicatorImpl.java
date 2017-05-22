package org.assignment.directoryreplicator;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.log4j.Logger;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

public class DirReplicatorImpl implements DirReplicator {
   private final String sourceIP;
   private final String[] destinationIPs;
   private final String directoryPath;
   private static final Logger logger = Logger.getLogger(DirReplicatorImpl.class);
   private static final int port = 6666;

   DirReplicatorImpl(String sourceIP, String directoryPath, String... destinationIPs) {
      this.sourceIP = sourceIP;
      this.destinationIPs = destinationIPs;
      this.directoryPath = directoryPath;
   }

   @Override
   public void start() {
      Node prevNodeTree = new Node();
      List<Node> filesToCreate = new ArrayList();
      List<Node> filesToDelete = new ArrayList();
      List<Node> filesToModified = new ArrayList();
      Boolean exists = checkDirectoryExists(directoryPath);
      if (!exists) {
         createDirectory(directoryPath);
      }
      makeFileHierarchy(directoryPath, prevNodeTree);
      getNodeListFromNodeTree(prevNodeTree, filesToCreate);
      sendChanges(true, filesToCreate, filesToDelete, filesToModified);
      try {
         TimeUnit.SECONDS.sleep(30);
      } catch (InterruptedException e) {
         logger.error(e.getStackTrace());
      }
      while (true) {
         Node newNodeTree = new Node();
         makeFileHierarchy(directoryPath, newNodeTree);
         filesToCreate = new ArrayList();
         filesToDelete = new ArrayList();
         filesToModified = new ArrayList();
         findChangesInDirectory(prevNodeTree, newNodeTree, filesToCreate, filesToDelete, filesToModified);
         if (!(filesToCreate.isEmpty() && filesToDelete.isEmpty() && filesToModified.isEmpty())) {
            sendChanges(false, filesToCreate, filesToDelete, filesToModified);
         }
         prevNodeTree = newNodeTree;
         try {
            TimeUnit.MINUTES.sleep(30);
         } catch (InterruptedException e) {
            logger.error(e.getStackTrace());
         }
      }
   }

   private Boolean checkDirectoryExists(String directoryPath) {
      Path path = Paths.get(directoryPath);
      if (Files.exists(path)) {
         return true;
      }
      return false;
   }

   private void createDirectory(String directoryPath) {
      try {
         Files.createDirectory(Paths.get(directoryPath));
      } catch (IOException e) {
         logger.error(e.getStackTrace());
      }
   }

   private void getNodeListFromNodeTree(Node node, List<Node> files) {
      if (node != null) {
         files.add(node);
         if (node.children != null && !node.children.isEmpty()) {
            for (Node child : node.children) {
               getNodeListFromNodeTree(child, files);
            }
         }
      }
   }

   private void makeFileHierarchy(String directoryPath, Node node) {
      File directory = new File(directoryPath);
      node.file = directory;
      File[] files = directory.listFiles();
      for (File file : files) {
         if (file.isFile()) {
            Node child = new Node();
            child.file = file;
            child.md5hash = createMD5Hash(file);
            child.children = null;
            node.children.add(child);
         } else if (file.isDirectory()) {
            Node child = new Node();
            child.file = file;
            node.children.add(child);
            makeFileHierarchy(file.getAbsolutePath(), child);
         }
      }
   }

   private String createMD5Hash(File file) {
      try {
         FileInputStream fileInputStream = new FileInputStream(file);
         String md5Hash = DigestUtils.md5Hex(fileInputStream);
         return md5Hash;
      } catch (IOException e) {
         logger.error(e.getStackTrace());
      }
      return null;
   }

   private void findChangesInDirectory(Node prevNode, Node newNode, List<Node> filesToCreate, List<Node> filesToDelete, List<Node> filesToModified) {
      List<Node> matchNewNode = new ArrayList();
      List<Node> matchPrevNode = new ArrayList();
      if (!prevNode.file.getAbsolutePath().equals(newNode.file.getAbsolutePath())) {
         return;
      }
      List<Node> copyOfNewNodeChildren = new CopyOnWriteArrayList(newNode.children);
      List<Node> copyOfPrevNodeChildren = new CopyOnWriteArrayList(prevNode.children);
      for (Node newNodeChild : copyOfNewNodeChildren) {
         for (Node prevNodeChild : copyOfPrevNodeChildren) {
            if (newNodeChild.file.getAbsolutePath().equals(prevNodeChild.file.getAbsolutePath())) {
               matchNewNode.add(newNodeChild);
               copyOfNewNodeChildren.remove(newNodeChild);
               matchPrevNode.add(prevNodeChild);
               copyOfPrevNodeChildren.remove(prevNodeChild);
               break;
            }
         }
      }
      for (Node newNodeChild : copyOfNewNodeChildren) {
         getNodeListFromNodeTree(newNodeChild, filesToCreate);
      }
      for (Node prevNodeChild : copyOfPrevNodeChildren) {
         filesToDelete.add(prevNodeChild);
      }
      for (int i = 0; i < matchNewNode.size(); i++) {
         if (matchNewNode.get(i).file.isDirectory()) {
            findChangesInDirectory(matchPrevNode.get(i), matchNewNode.get(i), filesToCreate, filesToDelete, filesToModified);
         } else if (matchNewNode.get(i).file.isFile()) {
            if (findChangesInFile(matchPrevNode.get(i), matchNewNode.get(i))) {
               filesToModified.add(matchNewNode.get(i));
            }
         }
      }
   }

   private boolean findChangesInFile(Node prevNode, Node newNode) {
      if (prevNode.file.isFile() && newNode.file.isFile()) {
         if (!prevNode.md5hash.equals(newNode.md5hash)) {
            return true;
         }
      }
      return false;
   }

   private void sendChanges(boolean isInitial, List<Node> filesToCreate, List<Node> filesToDelete, List<Node> filesToModified) {
      for (String destinationIP : destinationIPs) {
         try {
            Socket socket = new Socket(destinationIP, port);
            ObjectOutputStream objectOutputStream = new ObjectOutputStream(socket.getOutputStream());
            objectOutputStream.writeBoolean(isInitial);
            File directory = new File(directoryPath);
            String directoryName = directory.getName();
            objectOutputStream.writeObject(directoryName);

            //send Files to Create
            objectOutputStream.writeBoolean(filesToCreate.isEmpty());
            if (!filesToCreate.isEmpty()) {
               int folderCount = 0;
               int fileCount = 0;
               for (Node node : filesToCreate) {
                  if (node.file.isDirectory() && node.file.getAbsolutePath() != directoryPath) {
                     folderCount = folderCount + 1;
                  } else if (node.file.isFile()) {
                     fileCount = fileCount + 1;
                  }
               }
               objectOutputStream.writeInt(folderCount);
               if (folderCount > 0) {
                  for (Node node : filesToCreate) {
                     if (node.file.isDirectory() && node.file.getAbsolutePath() != directoryPath) {
                        Path base = Paths.get(directoryPath);
                        Path absolute = Paths.get(node.file.getAbsolutePath());
                        Path relative = base.relativize(absolute);
                        objectOutputStream.writeObject(relative.toString());
                     }
                  }
               }
               objectOutputStream.writeInt(fileCount);
               if (fileCount > 0) {
                  for (Node node : filesToCreate) {
                     if (node.file.isFile()) {
                        Path base = Paths.get(directoryPath);
                        Path absolute = Paths.get(node.file.getAbsolutePath());
                        Path relative = base.relativize(absolute);
                        objectOutputStream.writeLong(node.file.length());
                        objectOutputStream.writeObject(relative.toString());
                        if (node.file.length() > 0) {
                           byte[] mybytearray = new byte[(int) node.file.length()];
                           FileInputStream fileInputStream = new FileInputStream(node.file);
                           BufferedInputStream bufferedInputStream = new BufferedInputStream(fileInputStream);
                           bufferedInputStream.read(mybytearray, 0, mybytearray.length);
                           OutputStream outputStream = socket.getOutputStream();
                           outputStream.write(mybytearray, 0, mybytearray.length);
                           outputStream.flush();
                        }
                     }
                  }
               }
            }

            //send File To Delete
            objectOutputStream.writeBoolean(filesToDelete.isEmpty());
            if (!filesToDelete.isEmpty()) {
               objectOutputStream.writeInt(filesToDelete.size());
               for (Node node : filesToDelete) {
                  Path base = Paths.get(directoryPath);
                  Path absolute = Paths.get(node.file.getAbsolutePath());
                  Path relative = base.relativize(absolute);
                  objectOutputStream.writeObject(relative.toString());
               }
            }

            //send File to Modified
            objectOutputStream.writeBoolean(filesToModified.isEmpty());
            if (!filesToModified.isEmpty()) {
               objectOutputStream.writeInt(filesToModified.size());
               for (Node node : filesToModified) {
                  Path base = Paths.get(directoryPath);
                  Path absolute = Paths.get(node.file.getAbsolutePath());
                  Path relative = base.relativize(absolute);
                  objectOutputStream.writeLong(node.file.length());
                  objectOutputStream.writeObject(relative.toString());
                  if (node.file.length() > 0) {
                     FileInputStream fileInputStream = null;
                     BufferedInputStream bufferedInputStream = null;
                     OutputStream outputStream = null;
                     byte[] mybytearray = new byte[(int) node.file.length()];
                     fileInputStream = new FileInputStream(node.file);
                     bufferedInputStream = new BufferedInputStream(fileInputStream);
                     bufferedInputStream.read(mybytearray, 0, mybytearray.length);
                     outputStream = socket.getOutputStream();
                     outputStream.write(mybytearray, 0, mybytearray.length);
                     outputStream.flush();
                  }
               }
            }
            objectOutputStream.flush();
            objectOutputStream.close();
            socket.close();
         } catch (IOException e) {
            logger.error(e.getStackTrace());
         }
      }
   }
}
