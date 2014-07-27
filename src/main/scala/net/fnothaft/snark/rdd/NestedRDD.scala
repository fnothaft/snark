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
package net.fnothaft.snark.rdd

import org.apache.spark.SparkContext._
import org.apache.spark.SparkContext
import org.apache.spark.rdd.RDD
import net.fnothaft.snark.SnarkContext._
import net.fnothaft.snark.{ ArrayStructure, NestedIndex }
import scala.annotation.tailrec
import scala.math.{ log, pow }
import scala.reflect.ClassTag

object NestedRDD {

  private var enableOptimization = false

  /**
   * Creates a flat index RDD with n values. Package private.
   *
   * @param sc SparkContext to use for creating this RDD.
   * @param n Number of values to have in RDD.
   * @return Returns an RDD containing indices from 0 to n.
   */
  private[rdd] def index(sc: SparkContext, n: Int): RDD[NestedIndex] = {
    var step = pow(2, (log(n.toDouble) / log(2.0)).toInt).toInt
    var rdd: RDD[NestedIndex] = sc.parallelize(Seq(NestedIndex(0, 0)))

    @tailrec def fillIn(step: Int, rdd: RDD[NestedIndex]): RDD[NestedIndex] = {
      if (step < 1) {
        rdd
      }
      else {
        fillIn(step / 2, rdd.flatMap(i => Seq(i, NestedIndex(0, i.idx + step))))
      }
    }

    fillIn(step, rdd).filter(_.idx < n)
  }

  /**
   * Builds a new nested RDD out of a currently available RDD. Package private.
   *
   * @param rdd RDD to build from.
   * @param structure Structure of this RDD.
   * @return Returns a nested RDD.
   */
  private[rdd] def apply[T](rdd: RDD[(NestedIndex, T)],
                            structure: ArrayStructure): NestedRDD[T] = {
    if (enableOptimization) {
      throw new IllegalArgumentException("Optimization not yet implemented.")
    }
    else {
      new NestedRDD[T](rdd, structure)
    }
  }

  /**
   * Enables or disables optimizations.
   *
   * @param b Boolean value that denotes whether optimizations should be enabled or not.
   */
  def setOptimizations(b: Boolean) {
    enableOptimization = b
  }
}

private[rdd] case class ScanHelper[T, U](originalValue: T) {
  var scanValue: Option[U] = None

  def scan(lvalue: U, op: (U, T) => U): U = {
    assert(!hasBeenProcessed, "Cannot scan value twice.")

    val afterScan = op(lvalue, originalValue)

    scanValue = Some(afterScan)

    afterScan
  }

  def hasBeenProcessed: Boolean = scanValue.isDefined
}

class NestedRDD[T](protected val rdd: RDD[(NestedIndex, T)],
                   protected val structure: ArrayStructure) extends Serializable {

  /**
   * Maps a function to every element of this RDD.
   *
   * @param op A function to map to every element of this RDD.
   * @return A new nested RDD with element type U. The RDD will have the same structure
   * as the RDD the map is called on.
   *
   * @see mapWithIndex
   */
  def map[U](op: T => U): NestedRDD[U] = {
    NestedRDD[U](rdd.map(kv => {
      val (idx, v) = kv

      (idx, op(v))
    }), structure)
  }

  /**
   * Maps a function to every element of this RDD, along with the index of each element.
   *
   * @param op A function that maps the value of a point, as well as it's index, to a new value.
   * @return A new nested RDD with element type U. This RDD will have the same structure as
   * the RDD the map is called on. Additionally, each point will retain it's index.
   *
   * @see map
   */
  def mapWithIndex[U](op: (T, NestedIndex) => U): NestedRDD[U] = {
    NestedRDD[U](rdd.map(kv => {
      val (idx, v) = kv

      (idx, op(v, idx))
    }), structure)
  }

  /**
   * Executes a function on every element of this RDD. Does not create a new RDD. May be
   * called to cause side effects.
   *
   * @param op Function to run on every element.
   */
  def foreach(op: T => Unit) {
    rdd.foreach(kv => op(kv._2))
  }

  /**
   * Executes a function on every element of this RDD, along with the index of the element.
   * Does not create a new RDD. May be called to cause side effects.
   *
   * @param op Function to run on every element.
   */
  def foreach(op: (NestedIndex, T) => Unit) {
    rdd.foreach(kv => op(kv._1, kv._2))
  }

  /**
   * Performs a reduction operation on this RDD.
   *
   * @param op The reducing function to use. This function should be associative and commutative.
   * @return Returns a single value derived by running the reduction function on all RDD elements.
   */
  def reduce(op: (T, T) => T)(implicit tTag: ClassTag[T]): T = {
    rdd.map(kv => {
      val (idx, v) = kv

      v
    }).reduce(op)
  }

  /**
   * Returns a count of the number of elements in this array.
   *
   * @note This method uses internal state and does not trigger any side execution. For behavior
   * similar to org.apache.spark.rdd.RDD.count, use countWithSideEffects
   *
   * @return The number of elements in this RDD.
   *
   * @see countWithSideEffects
   */
  def count: Long = structure.elements

  /**
   * Returns a count of the number of elements in this array.
   *
   * @note This method does not use internal state and behaves as org.apache.spark.rdd.RDD.count
   * does, at the cost of speed.
   *
   * @return The number of elements in this RDD.
   *
   * @see count
   */
  def countWithSideEffects: Long = {
    val cRdd = rdd.count
    val cStr = count

    assert(cStr == cRdd,
      "Array structure count (" + cStr + ") and RDD count (" + cRdd + ") disagree.")

    cRdd
  }

  /**
   * Flattens the nested structure of this RDD into a nested RDD with a single level of hierarchy.
   *
   * @return Returns a flat nested RDD.
   */
  def flatten()(implicit tTag: ClassTag[T]): NestedRDD[T] = {
    val idxRdd = NestedRDD.index(rdd.context, count.toInt)

    NestedRDD[T](idxRdd.zip(rdd.map(kv => kv._2)), structure)
  }

  /**
   * Performs an index-based combining operation.
   *
   * @param op Binary combining operation.
   * @param index Nested index RDD to use. Must have same structure as this nested RDD.
   */
  def combine(op: (T, T) => T)(index: NestedRDD[NestedIndex])(
    implicit tTag: ClassTag[T]): NestedRDD[T] = {
    assert(structure.equals(index.structure),
      "Cannot do a combine on two nested arrays with different sizes.")

    NestedRDD[T](rdd.zip(index.rdd)
      .map(kvk => {
        val ((_, v), (_, k)) = kvk

        (k, v)
      }).groupByKey()
      .map(kv => {
        val (k, s) = kv

        (k, s.reduce(op))
      }), structure)
  }

  /**
   *
   */
  def scan[U](zero: U)(op: (T, U) => U)(implicit tTag: ClassTag[T]): NestedRDD[U] = {
    val r = rdd.sortByKey()
      .cache

    ???
  }

  /**
   * Executes an elemental operation across two nested RDDs. The two nested RDDs must have the
   * same structure. In this operation, both elements at an index have a function applied to them.
   *
   * @param op Function to apply.
   * @param r Other nested RDD to perform P operation on. Must have the same structure as this RDD.
   */
  def p[U, V](op: (T, U) => V)(r: NestedRDD[U]): NestedRDD[V] = {
    assert(structure.equals(r.structure),
      "Cannot do a p-operation on two nested arrays with different sizes.")

    NestedRDD[V](rdd.zip(r.rdd)
      .map(kvp => {
        val ((idx, t), (idx2, u)): ((NestedIndex, T), (NestedIndex, U)) = kvp
        assert(idx == idx2)

        (idx, op(t, u))
      }), structure)
  }

  /**
   * Applies a reduce within each nested segment. This operates on all nested segments.
   *
   * @param op Reduction function to apply.
   * @return Returns a map, which maps each nested segment ID to the reduction value.
   */
  def segmentedReduce(op: (T, T) => T)(implicit tTag: ClassTag[T]): Map[Int, T] = {
    rdd.map(kv => (kv._1.nest, kv._2))
      .groupByKey()
      .map(ks => {
        val (k, s): (Int, Iterable[T]) = ks

        (k, s.reduce(op))
      }).collect
      .toMap
  }

  /**
   * Performs a scan on all of the segments of this RDD.
   *
   * @param op Function to use for the scan.
   * @param zero Zero value to use for the scan.
   * @return New RDD where each segment has been operated on by a scan.
   */
  def segmentedScan[U](zero: U)(op: (U, T) => U): NestedRDD[U] = {
    segmentedScan((0 until structure.nests).map(i => zero))(op)
  }

  /**
   * Performs a scan on all of the segments of this RDD, with a different zero value
   * per each segment.
   *
   * @param op Function to use for the scan.
   * @param zero Sequence of zero values to use for the scan.
   * @return New RDD where each segment has been operated on by a scan.
   */
  def segmentedScan[U](zeros: Seq[U])(op: (U, T) => U): NestedRDD[U] = {
    assert(zeros.length == structure.nests,
      "Zeros must match to structure of RDD.")

    NestedRDD[U](rdd.keyBy(kv => kv._1.nest)
      .groupByKey()
      .flatMap(ns => {
        val (n, s) = ns

        val zero = zeros(n)

        val sorted = s.toSeq.sortBy(kv => kv._1)

        val idx = sorted.map(kv => kv._1)
        val vals = sorted.map(kv => kv._2)
          .scanLeft(zero)(op)
          .dropRight(1)

        idx.zip(vals)
      }), structure)
  }

  /**
   * Returns the value at a certain nested index.
   */
  def get(idx: NestedIndex): T = {
    val collected = rdd.filter(kv => kv._1.equals(idx))
      .collect

    assert(collected.length != 0, "Value with index " + idx + " not found.")
    assert(collected.length == 1, "Cannot have more than one value with index " + idx)

    collected.head._2
  }

  /**
   * Forces a repartitioning. Must be called after any operation that changes the structure of
   * the RDD.
   */
  protected def repartition() = {}

  /**
   * Collects the nested RDD on the master.
   *
   * @return The RDD in an array.
   */
  def collect(): Array[(NestedIndex, T)] = {
    rdd.collect()
  }
}