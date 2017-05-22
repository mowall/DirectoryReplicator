package org.assignment.directoryreplicator;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

class Node {
   File file = null;
   String md5hash = null;
   List<Node> children = new ArrayList();
}
