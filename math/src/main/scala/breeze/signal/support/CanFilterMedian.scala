package breeze.signal.support

import breeze.signal.{filterMedian, OptOverhang}
import breeze.stats._
import breeze.linalg.{convert, DenseVector}
import breeze.util.quickSelectImpl
import breeze.macros.expand
import scala.collection.mutable
import breeze.numerics.isOdd

/**A relatively optimized median filter, using TreeSet
 * @author ktakagaki
 * @date 2/28/14.
 */
trait CanFilterMedian[Input] {
  def apply(data: DenseVector[Input], windowLength: Int, overhang: OptOverhang): DenseVector[Input]
}

object CanFilterMedian {

  //Int, Long and Float will calculate in Double (see algorithm, needs infinitesimal small numbers for ordering)
  @expand
  implicit def dvFilterMedianT[@expand.args(Int, Long, Float) T]: CanFilterMedian[T] = {

    new CanFilterMedian[T] {
      def apply(data: DenseVector[T], windowLength: Int, overhang: OptOverhang): DenseVector[T] = {

        require(isOdd(windowLength), "median filter can only take odd windowLength values, since even values will cause a half-frame time shift")
        require(data.length >= 3, "data must be longer than 3")
        require(windowLength >= 1, "window length must be longer than 1")

        if( windowLength == 1 ) data.copy
        else {
          val tempret = new Array[T](data.length)
          val halfWindow = (windowLength-1)/2

          //calculate beginning and end separately
          for( indexFromBeginning <- 0 until halfWindow ) tempret(indexFromBeginning) = median( data(0 to indexFromBeginning*2) )
          for( indexToEnd <- 0 until halfWindow ) tempret(data.length-indexToEnd-1) = median( data(data.length-2*indexToEnd-1 until data.length) )

          var index = 0
          val tempDataExtract = data(index until index + windowLength).toArray
          var (currentMean, currentPivotIndex) = quickSelectImpl(tempDataExtract, halfWindow)
          tempret(index) = currentMean
          index += 1

//          while( index < data.length - (windowLength-1)/2 ){
//            findAndReplaceInstanceInPlace( tempDataExtract, data(index-windowLength))
//            (currentMean, currentPivotIndex) = quickSelectImpl(tempDataExtract)
//          }

        }

        DenseVector.zeros[T](4)

      }

      def findAndReplaceInstanceInPlace( arr: Array[T], fromValue: T, toValue: T, pivotPoint: Int): Unit = {
        val pivotValue: T = arr(pivotPoint)
        var found = false

        if( fromValue == pivotValue ) {
          arr(pivotPoint) = toValue
          found = true
        } else if( fromValue < pivotValue ){
          var count = pivotPoint - 1
          while( count >= 0 ){
            if( arr(count) == fromValue ) {
              arr(count) = toValue
              count = Int.MinValue
              found = true
            }else {
              count -= 1
            }
          }
        } else { //if( fromValue > pivotValue ){
          var count = pivotPoint + 1
          while( count < arr.length ){
            if( arr(count) == fromValue ){
              arr(count) = toValue
              count = Int.MaxValue
              found = true
            }else {
              count += 1
            }
          }
        }

        require(found, "The fromValue was not found within the given array, something is wrong!")
      }
    }

  }

  //Double returns Double
  implicit def dvFilterMedianDouble: CanFilterMedian[Double] = {

    new CanFilterMedian[Double] {
      def apply(data: DenseVector[Double], windowLength: Int, overhang: OptOverhang): DenseVector[Double] = {

        require(isOdd(windowLength), "median filter can only take odd windowLength values, since even values will cause a half-frame time shift")

        val threadNo = 8 //reasonable for modern processors

        val windowLengthPre = windowLength/2
        val splitDataLength = data.length/threadNo

        var tempret =
          if( splitDataLength > windowLength*10*threadNo ){    //arbitrary cutoff for whether to parallelize or not

            //middle 6 data vectors
            var splitData: Array[Array[Double]] = (
              for(cnt <- 1 to threadNo - 2)
                yield data.slice(cnt*splitDataLength - windowLengthPre, (cnt+1)*splitDataLength + windowLengthPre).toArray
            ).toArray

            splitData = splitData.+:( //first data vector
              data.slice(0, splitDataLength + windowLengthPre).toArray
            )
            splitData = splitData.:+(
              data.slice((threadNo-1)*splitDataLength - windowLengthPre, data.length).toArray
            )

            //if( isOdd(windowLength) )
              splitData.par.flatMap( medianFilterImplOddNoOverhang(_, windowLength) ).toArray
            //else splitData.par.flatMap( medianFilterImplEvenDoubleNoOverhang(_, windowLength) )

          } else {

          //if( isOdd(windowLength) )
            medianFilterImplOddNoOverhang(data.toArray, windowLength)
          //else medianFilterImplEvenDoubleNoOverhang(data.toScalaVector, windowLength)

          }

      tempret = overhang match {
        case OptOverhang.PreserveLength => {
          val halfWindow = (windowLength - 1)/2//(windowLength+1)/2 - 1

          //pad both sides of the vector with medians with smaller windows
          (for(winLen <- 0 to halfWindow-1) yield median( data(0 to winLen * 2) )).toArray ++ tempret ++
          (for(winLen <- (- halfWindow) to -1 ) yield median( data( 2*winLen + 1 to -1) ))
        }
        case OptOverhang.None => tempret
        case opt: OptOverhang => {
          throw new IllegalArgumentException("filterMedian only supports overhang=OptOverhang.PreserveLength/None, does not support " + opt.toString )
        }
      }

        DenseVector( tempret )

      }


    }
  }


  /**Implementation, odd window*/
  def medianFilterImplOddNoOverhang(data: Array[Double], windowLength: Int): Array[Double] = {
    require(windowLength <= data.length)
    require(windowLength % 2 == 1)

    val middleIndex = windowLength/2  //(windowLength+1)/2 - 1

    //The queue stores data values in order, to facilitating popping from the TreeSet once the window passes by
    val queue = new mutable.Queue[Double]()
    //The TreeSet stores values within the current window, sorted, so that the median can be found easily
    //tried various collection classes, but TreeSet implementation is fastest by far
    var sortedData = new mutable.TreeSet[Double]()

    def addData(x: Double) = {
      val adjustedX: Double = adjustX(x) //adjusts the value slightly, so that equal values can be written into the TreeSet
      queue.enqueue( adjustedX )
      sortedData.+=( adjustedX )// = sortedData.+(adjustedX)
    }

    //recursive function to adjust values slightly, since TreeSet will ignore added values if they are already present
    def adjustX(x: Double): Double = if(sortedData.contains(x)) adjustX( x * 1.0000000001 + 1E-295 ) else x
//    def adjustX(x: Float): Float= if(sortedData.contains(x)) adjustX( x * 1.00001f + 1E-30f ) else x

    //add data points from the first window to the TreeSet
    for(cnt <- 0 until windowLength) addData( data(cnt) )

    //initialize return array
    val tempret = new Array[Double]( data.length - (windowLength - 1) )

    //loop variables
    var firstElement = 0
    var lastElementExclusive = windowLength

    while( firstElement < tempret.length - 1 ){
      //set current middle variable
      tempret(firstElement) = sortedData.toStream.apply(middleIndex)//toStream seems to be fastest way to access middle value

      val remove = queue.dequeue()
      sortedData = sortedData.-=(remove)//-(remove)
      addData( data(lastElementExclusive) )

      firstElement += 1
      lastElementExclusive += 1
    }
    //process last element separately
    tempret(firstElement) = sortedData.toStream.apply(middleIndex)

    tempret

  }

//  /**Implementation, even window*/
//  private def medianFilterImplEvenDoubleNoOverhang(data: Vector[Double], windowLength: Int): Vector[Double] = {
//    require(windowLength <= data.length)
//    require(windowLength % 2 == 0)
//
//    val middleFirstIndex = windowLength/2 - 1
//
//    //The queue stores data values in order, to facilitating popping from the TreeSet once the window passes by
//    val queue = new mutable.Queue[Double]()
//    //The TreeSet stores values within the current window, sorted, so that the median can be found easily
//    //tried various collection classes, but TreeSet implementation is fastest by far
//    var sortedData = new mutable.TreeSet[Double]()
//
//    def addData(x: Double) = {
//      val adjustedX = adjustX(x) //adjusts the value slightly, so that equal values can be written into the TreeSet
//      queue.enqueue( adjustedX )
//      sortedData.+=( adjustedX )// = sortedData.+(adjustedX)
//    }
//
//    //recursive function to adjust values slightly, since TreeSet will ignore added values if they are already present
//    def adjustX(x: Double): Double = if(sortedData.contains(x)) adjustX( x * 1.00000000001 + 1E-300 ) else x
//
//    //add data points from the first window to the TreeSet
//    for(cnt <- 0 until windowLength) addData( data(cnt) )
//
//    //initialize return array
//    val tempret = new Array[Double]( data.length - (windowLength - 1) )
//
//    //loop variables
//    var firstElement = 0
//    var lastElementExclusive = windowLength
//
//    while( firstElement < tempret.length ){
//      //set current middle variable
//      val tempStream = sortedData.toStream
//      tempret(firstElement) = (tempStream.apply(middleFirstIndex) + tempStream.apply(middleFirstIndex + 1))/2d
//
//      val remove = queue.dequeue()
//      sortedData = sortedData.-=(remove)//-(remove)
//      addData( data(lastElementExclusive) )
//
//      firstElement += 1
//      lastElementExclusive += 1
//    }
//
//    Vector(tempret)
//
//  }


}
