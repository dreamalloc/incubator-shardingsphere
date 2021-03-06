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

package org.apache.shardingsphere.orchestration.center.instance;

import com.google.common.util.concurrent.SettableFuture;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.ExponentialBackoffRetry;

import org.apache.shardingsphere.orchestration.center.listener.DataChangedEvent;
import org.apache.shardingsphere.orchestration.center.util.EmbedTestingServer;
import org.apache.shardingsphere.underlying.common.config.orchestration.CenterConfiguration;
import org.junit.BeforeClass;
import org.junit.Test;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;

public final class CuratorZookeeperCenterRepositoryTest {
    
    private static CuratorZookeeperCenterRepository centerRepository = new CuratorZookeeperCenterRepository();
    
    private static CuratorFramework client;
    
    private static final String SERVER_LISTS = "127.0.0.1:3181";
    
    @BeforeClass
    public static void init() {
        EmbedTestingServer.start();
        CenterConfiguration configuration = new CenterConfiguration(centerRepository.getType(), new Properties());
        configuration.setServerLists(SERVER_LISTS);
        centerRepository.init(configuration);
        client = CuratorFrameworkFactory.newClient(SERVER_LISTS, new ExponentialBackoffRetry(1000, 3));
        client.start();
    }
    
    @Test
    public void assertPersist() {
        centerRepository.persist("/test", "value1");
        assertThat(centerRepository.get("/test"), is("value1"));
    }
    
    @Test
    public void assertUpdate() {
        centerRepository.persist("/test", "value2");
        assertThat(centerRepository.get("/test"), is("value2"));
    }
    
    @Test
    public void assertPersistEphemeral() {
        centerRepository.persistEphemeral("/test/ephemeral", "value3");
        assertThat(centerRepository.get("/test/ephemeral"), is("value3"));
    }
    
    @Test
    public void assertGetChildrenKeys() {
        centerRepository.persist("/test/children/1", "value11");
        centerRepository.persist("/test/children/2", "value12");
        centerRepository.persist("/test/children/3", "value13");
        List<String> childrenKeys = centerRepository.getChildrenKeys("/test/children");
        assertThat(childrenKeys.size(), is(3));
    }
    
    @Test
    public void assertWatchUpdatedChangedType() throws Exception {
        centerRepository.persist("/test/children_updated/1", "value1");
        final SettableFuture<DataChangedEvent> future = SettableFuture.create();
        centerRepository.watch("/test/children_updated", future::set);
        centerRepository.persist("/test/children_updated/1", "value2");
        DataChangedEvent dataChangedEvent = future.get(5, TimeUnit.SECONDS);
        assertNotNull(dataChangedEvent);
        assertThat(dataChangedEvent.getChangedType(), is(DataChangedEvent.ChangedType.UPDATED));
        assertThat(dataChangedEvent.getKey(), is("/test/children_updated/1"));
        assertThat(dataChangedEvent.getValue(), is("value2"));
        assertThat(centerRepository.get("/test/children_updated/1"), is("value2"));
    }
    
    @Test
    public void assertWatchDeletedChangedType() throws Exception {
        centerRepository.persist("/test/children_deleted/5", "value5");
        SettableFuture<DataChangedEvent> future = SettableFuture.create();
        centerRepository.watch("/test/children_deleted/5", future::set);
        client.delete().forPath("/test/children_deleted/5");
        DataChangedEvent dataChangedEvent = future.get(5, TimeUnit.SECONDS);
        assertNotNull(dataChangedEvent);
        assertThat(dataChangedEvent.getChangedType(), is(DataChangedEvent.ChangedType.DELETED));
        assertThat(dataChangedEvent.getKey(), is("/test/children_deleted/5"));
        assertThat(dataChangedEvent.getValue(), is("value5"));
    }
    
    @Test
    public void assertWatchAddedChangedType() throws InterruptedException {
        centerRepository.persist("/test/children_added/4", "value4");
        AtomicReference<DataChangedEvent> actualDataChangedEvent = new AtomicReference<>();
        centerRepository.watch("/test/children_added", actualDataChangedEvent::set);
        Thread.sleep(2000L);
        assertNull(actualDataChangedEvent.get());
    }
    
    @Test
    public void assertGetWithNonExistentKey() {
        assertNull(centerRepository.get("/test/nonExistentKey"));
    }
    
    @Test
    public void assertBuildCuratorClientWithCustomConfig() {
        final CuratorZookeeperCenterRepository customCenterRepository = new CuratorZookeeperCenterRepository();
        Properties properties = new Properties();
        properties.setProperty(ZookeeperPropertyKey.RETRY_INTERVAL_MILLISECONDS.getKey(), "1000");
        properties.setProperty(ZookeeperPropertyKey.MAX_RETRIES.getKey(), "1");
        properties.setProperty(ZookeeperPropertyKey.TIME_TO_LIVE_SECONDS.getKey(), "100");
        properties.setProperty(ZookeeperPropertyKey.OPERATION_TIMEOUT_MILLISECONDS.getKey(), "1000");
        EmbedTestingServer.start();
        CenterConfiguration configuration = new CenterConfiguration(customCenterRepository.getType(), new Properties());
        configuration.setServerLists(SERVER_LISTS);
        customCenterRepository.setProperties(properties);
        customCenterRepository.init(configuration);
        assertThat(customCenterRepository.getProperties().getProperty(ZookeeperPropertyKey.RETRY_INTERVAL_MILLISECONDS.getKey()), is("1000"));
        assertThat(customCenterRepository.getProperties().getProperty(ZookeeperPropertyKey.MAX_RETRIES.getKey()), is("1"));
        assertThat(customCenterRepository.getProperties().getProperty(ZookeeperPropertyKey.TIME_TO_LIVE_SECONDS.getKey()), is("100"));
        assertThat(customCenterRepository.getProperties().getProperty(ZookeeperPropertyKey.OPERATION_TIMEOUT_MILLISECONDS.getKey()), is("1000"));
        customCenterRepository.persist("/test/children/1", "value1");
        assertThat(customCenterRepository.get("/test/children/1"), is("value1"));
    }
    
    @Test
    public void assertBuildCuratorClientWithTimeToLiveSecondsEqualsZero() {
        final CuratorZookeeperCenterRepository customCenterRepository = new CuratorZookeeperCenterRepository();
        Properties properties = new Properties();
        properties.setProperty(ZookeeperPropertyKey.TIME_TO_LIVE_SECONDS.getKey(), "0");
        EmbedTestingServer.start();
        CenterConfiguration configuration = new CenterConfiguration(customCenterRepository.getType(), new Properties());
        configuration.setServerLists(SERVER_LISTS);
        customCenterRepository.setProperties(properties);
        customCenterRepository.init(configuration);
        assertThat(customCenterRepository.getProperties().getProperty(ZookeeperPropertyKey.TIME_TO_LIVE_SECONDS.getKey()), is("0"));
        customCenterRepository.persist("/test/children/1", "value1");
        assertThat(customCenterRepository.get("/test/children/1"), is("value1"));
    }
    
    @Test
    public void assertBuildCuratorClientWithOperationTimeoutMillisecondsEqualsZero() {
        final CuratorZookeeperCenterRepository customCenterRepository = new CuratorZookeeperCenterRepository();
        Properties properties = new Properties();
        properties.setProperty(ZookeeperPropertyKey.OPERATION_TIMEOUT_MILLISECONDS.getKey(), "0");
        EmbedTestingServer.start();
        CenterConfiguration configuration = new CenterConfiguration(customCenterRepository.getType(), new Properties());
        configuration.setServerLists(SERVER_LISTS);
        customCenterRepository.setProperties(properties);
        customCenterRepository.init(configuration);
        assertThat(customCenterRepository.getProperties().getProperty(ZookeeperPropertyKey.OPERATION_TIMEOUT_MILLISECONDS.getKey()), is("0"));
        customCenterRepository.persist("/test/children/1", "value1");
        assertThat(customCenterRepository.get("/test/children/1"), is("value1"));
    }
    
    @Test
    public void assertBuildCuratorClientWithDigest() {
        final CuratorZookeeperCenterRepository customCenterRepository = new CuratorZookeeperCenterRepository();
        Properties properties = new Properties();
        properties.setProperty(ZookeeperPropertyKey.DIGEST.getKey(), "any");
        EmbedTestingServer.start();
        CenterConfiguration configuration = new CenterConfiguration(customCenterRepository.getType(), new Properties());
        configuration.setServerLists(SERVER_LISTS);
        customCenterRepository.setProperties(properties);
        customCenterRepository.init(configuration);
        assertThat(customCenterRepository.getProperties().getProperty(ZookeeperPropertyKey.DIGEST.getKey()), is("any"));
        customCenterRepository.persist("/test/children/1", "value1");
        assertThat(customCenterRepository.get("/test/children/1"), is("value1"));
    }
}
