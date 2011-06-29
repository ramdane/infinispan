/*
 * JBoss, Home of Professional Open Source
 * Copyright 2010 Red Hat Inc. and/or its affiliates and other
 * contributors as indicated by the @author tags. All rights reserved.
 * See the copyright.txt in the distribution for a full listing of
 * individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.infinispan.server.hotrod.test

import java.util.concurrent.atomic.AtomicInteger
import java.lang.reflect.Method
import org.infinispan.server.hotrod.OperationStatus._
import org.testng.Assert._
import org.infinispan.server.hotrod._
import logging.Log
import org.infinispan.config.Configuration.CacheMode
import org.infinispan.config.Configuration
import org.infinispan.manager.EmbeddedCacheManager
import org.infinispan.server.core.Main._
import java.util.{Properties, Arrays}
import org.infinispan.util.{TypedProperties, Util}

/**
 * Test utils for Hot Rod tests.
 * 
 * @author Galder Zamarreño
 * @since 4.1
 */
object HotRodTestingUtil extends Log {

   val EXPECTED_HASH_FUNCTION_VERSION: Byte = 2

   def host = "127.0.0.1"

   def startHotRodServer(manager: EmbeddedCacheManager): HotRodServer =
      startHotRodServer(manager, UniquePortThreadLocal.get.intValue)

   def startHotRodServer(manager: EmbeddedCacheManager, proxyHost: String, proxyPort: Int): HotRodServer =
      startHotRodServer(manager, UniquePortThreadLocal.get.intValue, 0, proxyHost, proxyPort)

   def startHotRodServer(manager: EmbeddedCacheManager, port: Int): HotRodServer =
      startHotRodServer(manager, port, 0)

   def startHotRodServer(manager: EmbeddedCacheManager, port:Int, proxyHost: String, proxyPort: Int): HotRodServer =
      startHotRodServer(manager, port, 0, proxyHost, proxyPort)

   def startHotRodServer(manager: EmbeddedCacheManager, port: Int, idleTimeout: Int): HotRodServer =
      startHotRodServer(manager, port, idleTimeout, host, port)

   def startHotRodServer(manager: EmbeddedCacheManager, port: Int, idleTimeout: Int, proxyHost: String, proxyPort: Int): HotRodServer =
      startHotRodServer(manager, port, idleTimeout, proxyHost, proxyPort, -1)

   def startHotRodServerWithDelay(manager: EmbeddedCacheManager, port: Int, delay: Long): HotRodServer =
      startHotRodServer(manager, port, 0, host, port, delay)

   def startHotRodServer(manager: EmbeddedCacheManager, port: Int, idleTimeout: Int,
                         proxyHost: String, proxyPort: Int, delay: Long): HotRodServer = {
      val properties = new Properties
      properties.setProperty(PROP_KEY_IDLE_TIMEOUT, idleTimeout.toString)
      properties.setProperty(PROP_KEY_PROXY_HOST, proxyHost)
      properties.setProperty(PROP_KEY_PROXY_PORT, proxyPort.toString)
      startHotRodServer(manager, port, delay, properties)
   }

   def startHotRodServer(manager: EmbeddedCacheManager, port: Int, props: Properties): HotRodServer =
      startHotRodServer(manager, port, 0, props)

   def startHotRodServer(manager: EmbeddedCacheManager, port: Int, delay: Long, props: Properties): HotRodServer = {
      val server = new HotRodServer {
         override protected def createTopologyCacheConfig(typedProps: TypedProperties): Configuration = {
            if (delay > 0)
               Thread.sleep(delay)

            val cfg = super.createTopologyCacheConfig(typedProps)
            cfg.setSyncCommitPhase(true) // Only for testing, so that asserts work fine.
            cfg.setSyncRollbackPhase(true) // Only for testing, so that asserts work fine.
            cfg
         }
      }
      props.setProperty(PROP_KEY_HOST, host)
      props.setProperty(PROP_KEY_PORT, port.toString)
      server.start(props, manager)



      server
   }

   private def getProperties(host: String, port: Int, idleTimeout: Int, proxyHost: String, proxyPort: Int): Properties = {
      val properties = new Properties
      properties.setProperty(PROP_KEY_HOST, host)
      properties.setProperty(PROP_KEY_PORT, port.toString)
      properties.setProperty(PROP_KEY_IDLE_TIMEOUT, idleTimeout.toString)
      properties.setProperty(PROP_KEY_PROXY_HOST, proxyHost)
      properties.setProperty(PROP_KEY_PROXY_PORT, proxyPort.toString)
      properties
   }

   def startCrashingHotRodServer(manager: EmbeddedCacheManager, port: Int): HotRodServer = {
      val server = new HotRodServer {
         override protected def createTopologyCacheConfig(typedProps: TypedProperties): Configuration = {
            val cfg = super.createTopologyCacheConfig(typedProps)
            cfg.setSyncCommitPhase(true) // Only for testing, so that asserts work fine.
            cfg.setSyncRollbackPhase(true) // Only for testing, so that asserts work fine.
            cfg
         }

         override protected def removeSelfFromTopologyView {
            // Empty to emulate a member that's crashed/unresponsive and has not executed removal,
            // but has been evicted from JGroups cluster.
         }
      }
      server.start(getProperties(host, port, 0, host, port), manager)
      server
   }

   def k(m: Method, prefix: String): Array[Byte] = {
      val bytes: Array[Byte] = (prefix + m.getName).getBytes
      trace("String %s is converted to %s bytes", prefix + m.getName, Util.printArray(bytes, true))
      bytes
   }

   def v(m: Method, prefix: String): Array[Byte] = k(m, prefix)

   def k(m: Method): Array[Byte] = k(m, "k-")

   def v(m: Method): Array[Byte] = v(m, "v-")

   def assertStatus(resp: TestResponse, expected: OperationStatus): Boolean = {
      val status = resp.status
      val isSuccess = status == expected
      resp match {
         case e: TestErrorResponse =>
            assertTrue(isSuccess,
               "Status should have been '%s' but instead was: '%s', and the error message was: %s"
               .format(expected, status, e.msg))
         case _ => assertTrue(isSuccess,
               "Status should have been '%s' but instead was: '%s'"
               .format(expected, status))
      }
      isSuccess
   }

   def assertSuccess(resp: TestGetResponse, expected: Array[Byte]): Boolean = {
      assertStatus(resp, Success)
      val isArrayEquals = Arrays.equals(expected, resp.data.get)
      assertTrue(isArrayEquals, "Retrieved data should have contained " + Util.printArray(expected, true)
            + " (" + new String(expected) + "), but instead we received " + Util.printArray(resp.data.get, true) + " (" +  new String(resp.data.get) +")")
      isArrayEquals
   }

   def assertSuccess(resp: TestGetWithVersionResponse, expected: Array[Byte], expectedVersion: Int): Boolean = {
      assertTrue(resp.version != expectedVersion)
      assertSuccess(resp, expected)
   }

   def assertSuccess(resp: TestResponseWithPrevious, expected: Array[Byte]): Boolean = {
      assertStatus(resp, Success)
      val isSuccess = Arrays.equals(expected, resp.previous.get)
      assertTrue(isSuccess)
      isSuccess
   }

   def assertKeyDoesNotExist(resp: TestGetResponse): Boolean = {
      val status = resp.status
      assertTrue(status == KeyDoesNotExist, "Status should have been 'KeyDoesNotExist' but instead was: " + status)
      assertEquals(resp.data, None)
      status == KeyDoesNotExist
   }

   def assertTopologyReceived(topoResp: AbstractTopologyResponse, servers: List[HotRodServer]) {
      assertEquals(topoResp.view.topologyId, 2)
      assertEquals(topoResp.view.members.size, 2)
      assertAddressEquals(topoResp.view.members.head, servers.head.getAddress)
      assertAddressEquals(topoResp.view.members.tail.head, servers.tail.head.getAddress)
   }

   def assertAddressEquals(actual: TopologyAddress, expected: TopologyAddress) {
      assertEquals(actual.host, expected.host)
      assertEquals(actual.port, expected.port)
   }

   def assertHashTopologyReceived(topoResp: AbstractTopologyResponse, servers: List[HotRodServer], hashIds: List[Map[String, Int]]) {
      val hashTopologyResp = topoResp.asInstanceOf[HashDistAwareResponse]
      assertEquals(hashTopologyResp.view.topologyId, 2)
      assertEquals(hashTopologyResp.view.members.size, 2)
      assertAddressEquals(hashTopologyResp.view.members.head, servers.head.getAddress, hashIds.head)
      assertAddressEquals(hashTopologyResp.view.members.tail.head, servers.tail.head.getAddress, hashIds.tail.head)
      assertEquals(hashTopologyResp.numOwners, 2)
      assertEquals(hashTopologyResp.hashFunction, EXPECTED_HASH_FUNCTION_VERSION)
      assertEquals(hashTopologyResp.hashSpace, 10240)
   }

   def assertNoHashTopologyReceived(topoResp: AbstractTopologyResponse, servers: List[HotRodServer], hashIds: List[Map[String, Int]]) {
      val hashTopologyResp = topoResp.asInstanceOf[HashDistAwareResponse]
      assertEquals(hashTopologyResp.view.topologyId, 2)
      assertEquals(hashTopologyResp.view.members.size, 2)
      assertAddressEquals(hashTopologyResp.view.members.head, servers.head.getAddress, hashIds.head)
      assertAddressEquals(hashTopologyResp.view.members.tail.head, servers.tail.head.getAddress, hashIds.tail.head)
      assertEquals(hashTopologyResp.numOwners, 0)
      assertEquals(hashTopologyResp.hashFunction, 0)
      assertEquals(hashTopologyResp.hashSpace, 0)
   }

   def assertAddressEquals(actual: TopologyAddress, expected: TopologyAddress, expectedHashIds: Map[String, Int]) {
      assertEquals(actual.host, expected.host)
      assertEquals(actual.port, expected.port)
      assertEquals(actual.hashIds, expectedHashIds)
   }

   private def createTopologyCacheConfig: Configuration = {
      val topologyCacheConfig = new Configuration
      topologyCacheConfig.setCacheMode(CacheMode.REPL_SYNC)
      topologyCacheConfig.setSyncReplTimeout(10000) // Milliseconds
      topologyCacheConfig.setFetchInMemoryState(true) // State transfer required
      topologyCacheConfig.setSyncCommitPhase(true) // Only for testing, so that asserts work fine.
      topologyCacheConfig.setSyncRollbackPhase(true) // Only for testing, so that asserts work fine.
      topologyCacheConfig
   }
   
} 

object UniquePortThreadLocal extends ThreadLocal[Int] {
   private val uniqueAddr = new AtomicInteger(12311)
   override def initialValue: Int = uniqueAddr.getAndAdd(100)
}
