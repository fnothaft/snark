/**
 * Copyright 2014 Frank Austin Nothaft
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.fnothaft.snark.util

import org.scalatest.{ Tag, BeforeAndAfter, FunSuite }
import org.apache.spark.{ SparkConf, SparkContext }
import java.net.ServerSocket
import org.apache.log4j.{ Level, Logger }

object SparkTest extends Tag("net.fnothaft.snark.util.SparkFunSuite")

/**
 * This builds heavily on top of org.bdgenomics.adam.util.SparkFunSuite.
 */
trait SparkFunSuite extends FunSuite with BeforeAndAfter {

  var sc: SparkContext = _

  def setupSparkContext(sparkName: String, silenceSpark: Boolean = true) {
    // Silence the Spark logs
    Seq("spark", "org.eclipse.jetty", "akka").foreach(name => {
      Logger.getLogger(name).setLevel(Level.WARN)
    })

    synchronized {
      // Find an unused port
      val s = new ServerSocket(0)
      val driverPort = Some(s.getLocalPort)
      s.close()
      sc = new SparkContext(new SparkConf(true).setAppName("snark")
        .setMaster("local[4]"))
    }
  }

  def teardownSparkContext() {
    // Stop the context
    sc.stop()
    sc = null
  }

  def sparkBefore(beforeName: String, silenceSpark: Boolean = true)(body: => Unit) {
    before {
      setupSparkContext(beforeName, silenceSpark)
      try {
        // Run the before block
        body
      } finally {
        teardownSparkContext()
      }
    }
  }

  def sparkAfter(beforeName: String, silenceSpark: Boolean = true)(body: => Unit) {
    after {
      setupSparkContext(beforeName, silenceSpark)
      try {
        // Run the after block
        body
      } finally {
        teardownSparkContext()
      }
    }
  }

  def sparkTest(name: String, silenceSpark: Boolean, tags: Tag*)(body: => Unit) {
    test(name, SparkTest +: tags: _*) {
      setupSparkContext(name, silenceSpark)
      try {
        // Run the test
        body
      } finally {
        teardownSparkContext()
      }
    }
  }

  def sparkTest(name: String)(body: => Unit) {
    sparkTest(name, silenceSpark = true)(body)
  }
}

