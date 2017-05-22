package org.assignment.directoryreplicator;

public class ApplicationStart {
   public static void main(String[] Args){
      DirReplicator dirReplicator = new DirReplicatorImpl("","/home/mowall/DirToSpy","localhost");
      dirReplicator.start();
   }
}
