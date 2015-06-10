/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.solr.core;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hdfs.MiniDFSCluster;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.IndexOutput;
import org.apache.solr.SolrTestCaseJ4;
import org.apache.solr.cloud.hdfs.HdfsTestUtil;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.core.DirectoryFactory.DirContext;
import org.apache.solr.store.hdfs.HdfsLocalityReporter;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import com.carrotsearch.randomizedtesting.annotations.ThreadLeakScope;
import com.carrotsearch.randomizedtesting.annotations.ThreadLeakScope.Scope;

@ThreadLeakScope(Scope.NONE) // hdfs client currently leaks thread (HADOOP-9049)
public class HdfsDirectoryFactoryTest extends SolrTestCaseJ4 {
  
  private static MiniDFSCluster dfsCluster;
  
  @BeforeClass
  public static void setupClass() throws Exception {
    dfsCluster = HdfsTestUtil.setupClass(createTempDir().getAbsolutePath());
  }
  
  @AfterClass
  public static void teardownClass() throws Exception {
    HdfsTestUtil.teardownClass(dfsCluster);
    System.clearProperty("solr.hdfs.home");
    System.clearProperty(HdfsDirectoryFactory.NRTCACHINGDIRECTORY_MAXMERGESIZEMB);
    dfsCluster = null;
  }

  @Test
  public void testLocalityReporter() throws Exception {
    Configuration conf = HdfsTestUtil.getClientConfiguration(dfsCluster);
    conf.set("dfs.permissions.enabled", "false");
    
    HdfsDirectoryFactory factory = new HdfsDirectoryFactory();
    Map<String,String> props = new HashMap<String,String>();
    props.put(HdfsDirectoryFactory.HDFS_HOME, HdfsTestUtil.getURI(dfsCluster) + "/solr");
    props.put(HdfsDirectoryFactory.BLOCKCACHE_ENABLED, "false");
    props.put(HdfsDirectoryFactory.NRTCACHINGDIRECTORY_ENABLE, "false");
    factory.init(new NamedList<>(props));
    
    Iterator<SolrInfoMBean> it = factory.offerMBeans().iterator();
    it.next(); // skip
    SolrInfoMBean localityBean = it.next(); // brittle, but it's ok
    
    // Make sure we have the right bean.
    assertEquals("hdfs-locality", localityBean.getName());
    
    // We haven't done anything, so there should be no data
    NamedList<?> statistics = localityBean.getStatistics();
    assertEquals(0l, statistics.get(HdfsLocalityReporter.LOCALITY_BYTES_TOTAL));
    assertEquals(0, statistics.get(HdfsLocalityReporter.LOCALITY_BYTES_RATIO));
    
    // create a directory and a file
    String path = HdfsTestUtil.getURI(dfsCluster) + "/solr3/";
    Directory dir = factory.create(path, DirContext.DEFAULT);
    try(IndexOutput writer = dir.createOutput("output", null)) {
      writer.writeLong(42l);
    }
    
    final long long_bytes = Long.SIZE / Byte.SIZE;
    
    // no locality because hostname not set
    statistics = localityBean.getStatistics();
    assertEquals(long_bytes, statistics.get(HdfsLocalityReporter.LOCALITY_BYTES_TOTAL));
    assertEquals(1, statistics.get(HdfsLocalityReporter.LOCALITY_BLOCKS_TOTAL));
    assertEquals(0, statistics.get(HdfsLocalityReporter.LOCALITY_BLOCKS_LOCAL));
    
    // set hostname and check again
    factory.setHost("127.0.0.1");
    statistics = localityBean.getStatistics();
    assertEquals(long_bytes, statistics.get(HdfsLocalityReporter.LOCALITY_BYTES_LOCAL));
    
    factory.close();
  }
}
