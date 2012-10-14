/*
 *  @author Philip Stutz
 *
 *  Copyright 2012 University of Zurich
 *      
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *  
 *         http://www.apache.org/licenses/LICENSE-2.0
 *  
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.signalcollect.storage

import com.signalcollect.interfaces.VertexStore
import com.signalcollect.Vertex
import scala.util.MurmurHash
import scala.annotation.tailrec

// A special adaptation of IntHashMap[Vertex[_, _]].
// We allow arbitrary types for the vertex id to make
// the usage of the framework simple.
// This unfortunately means that we cannot use the id
// as the key, as these keys might be expensive to
// compare and require more space than an array of Ints.
// As a proxy we use the hashCode of a vertex id as
// the key in this map. In order to handle (rare) collisions,
// we have to do an additional check to verify that the vertex id
// matches indeed (and not just the hash of the vertex id).
class VertexMap(
    initialSize: Int = 32768,
    rehashFraction: Float = 0.75f) extends VertexStore {
  assert(initialSize > 0)
  private[this] final var maxSize = nextPowerOfTwo(initialSize)
  assert(maxSize > 0 && maxSize >= initialSize, "Initial size is too large.")
  private[this] final var maxElements: Int = (rehashFraction * maxSize).floor.toInt
  private[this] final var values = new Array[Vertex[_, _]](maxSize)
  private[this] final var keys = new Array[Int](maxSize) // 0 means empty
  private[this] final var mask = maxSize - 1
  private[this] final var nextPositionToProcess = 0

  final override def size = numberOfElements
  final def isEmpty = numberOfElements == 0
  private[this] final var numberOfElements = 0

  final def clear {
    values = new Array[Vertex[_, _]](maxSize)
    keys = new Array[Int](maxSize)
    numberOfElements = 0
    nextPositionToProcess = 0
  }

  final def foreach[U](f: Vertex[_, _] => U) {
    var i = 0
    var elementsProcessed = 0
    while (elementsProcessed < numberOfElements) {
      val vertex = values(i)
      if (vertex != null) {
        f(vertex)
        elementsProcessed += 1
      }
      i += 1
    }
  }

  // Removes the vertices after they have been processed.
  final def process[U](p: Vertex[_, _] => U, numberOfVertices: Option[Int] = None) {
    val limit = math.min(numberOfElements, numberOfVertices.getOrElse(numberOfElements))
    var elementsProcessed = 0
    while (elementsProcessed < limit) {
      val vertex = values(nextPositionToProcess)
      if (vertex != null) {
        p(vertex)
        elementsProcessed += 1
        // Don't optimize, most of the next entries get removed anyway.
        keys(nextPositionToProcess) = 0
        values(nextPositionToProcess) = null
        numberOfElements -= 1
      }
      nextPositionToProcess = (nextPositionToProcess + 1) & mask
    }
  }

  private[this] final def tryDouble {
    // 1073741824 is the largest size and cannot be doubled anymore.
    if (maxSize != 1073741824) {
      val oldSize = maxSize
      val oldValues = values
      val oldKeys = keys
      val oldNumberOfElements = numberOfElements
      maxSize *= 2
      maxElements = (rehashFraction * maxSize).floor.toInt
      values = new Array[Vertex[_, _]](maxSize)
      keys = new Array[Int](maxSize)
      mask = maxSize - 1
      numberOfElements = 0
      var i = 0
      var elementsMoved = 0
      while (elementsMoved < oldNumberOfElements) {
        if (oldKeys(i) != 0) {
          put(oldValues(i))
          elementsMoved += 1
        }
        i += 1
      }
    }
  }

  final def remove(vertexId: Any) {
    remove(vertexId, true)
  }

  private final def remove(vertexId: Any, optimize: Boolean) {
    val key = idToKey(vertexId)
    var position = keyToPosition(key)
    var keyAtPosition = keys(position)
    while (keyAtPosition != 0 && key != keyAtPosition && vertexId != values(position).id) {
      position = (position + 1) & mask
      keyAtPosition = keys(position)
    }
    // We can only remove the entry if it was found.
    if (keyAtPosition != 0) {
      keys(position) = 0
      values(position) = null
      numberOfElements -= 1
      if (optimize) {
        // Try to reinsert all elements that are not optimally placed until an empty position is found.
        // See http://stackoverflow.com/questions/279539/best-way-to-remove-an-entry-from-a-hash-table
        position = ((position + 1) & mask)
        keyAtPosition = keys(position)
        while (keyAtPosition != 0) {
          if ((keyAtPosition & mask) != position) {
            val vertex = values(position)
            keys(position) = 0
            values(position) = null
            numberOfElements -= 1
            putWithKey(keyAtPosition, vertex)
          }
          position = ((position + 1) & mask)
          keyAtPosition = keys(position)
        }
      }
    }
  }

  final def get(vertexId: Any): Vertex[_, _] = {
    val key = idToKey(vertexId)
    var position = keyToPosition(key)
    var keyAtPosition = keys(position)
    while (keyAtPosition != 0 && key != keyAtPosition && vertexId != values(position).id) {
      position = (position + 1) & mask
      keyAtPosition = keys(position)
    }
    if (keyAtPosition != 0) {
      values(position)
    } else {
      null
    }
  }

  // Only put if no vertex with the same id is present. If a vertex was put, return true.
  final def put(vertex: Vertex[_, _]): Boolean = {
    val key = idToKey(vertex.id)
    putWithKey(key, vertex)
  }

  private[this] final def putWithKey(key: Int, vertex: Vertex[_, _]): Boolean = {
    var position = keyToPosition(key)
    var keyAtPosition = keys(position)
    while (keyAtPosition != 0 && key != keyAtPosition && vertex.id != values(position).id) {
      position = (position + 1) & mask
      keyAtPosition = keys(position)
    }
    var doPut = keyAtPosition == 0
    if (doPut) {
      keys(position) = key
      values(position) = vertex
      numberOfElements += 1
      if (numberOfElements >= maxElements) {
        if (numberOfElements >= maxSize) {
          throw new OutOfMemoryError("The hash map is full and cannot be expanded any further.")
        }
        tryDouble
      }
    }
    doPut
  }

  private[this] final def keyToPosition(key: Int) = {
    key & mask
  }

  private[this] final def idToKey(vertexId: Any) = {
    // No key can be 0, because 0 is reserved for no-entry.
    // This is why zero gets mapped to Int.MinValue.
    // The increase in collisions should be negligible,
    // because it is just one key and because usually that bits gets
    // cut off by the mask.
    // I tried some bit twiddling to avoid an if statement
    // (i  + ((((i + Int.MaxValue) >> 31) & i >> 31) << 31)),
    // but the compiler seems to be smarter about optimizing this.
    val hashCode = vertexId.hashCode
    if (hashCode == 0) Int.MinValue else hashCode
  }

  private[this] final def nextPowerOfTwo(x: Int): Int = {
    assert(x > 0)
    var r = x - 1
    r |= r >> 1
    r |= r >> 2
    r |= r >> 4
    r |= r >> 8
    r |= r >> 16
    r + 1
  }

}