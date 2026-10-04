package com.baidu.carlife.sdk.util

object ScaleUtils {
    /**
     * 在长宽为width和height的矩形内部，长宽比为expectRatio的最大矩形
     */
    fun inside(width: Int, height: Int, expectRatio: Float): Pair<Int, Int> {
        val ratio = width / height.toFloat()
        if (ratio > expectRatio) {
            val newWidth = (height * expectRatio).toInt()
            return Pair(newWidth, height)
        }
        val newHeight = (width / expectRatio).toInt()
        return Pair(width, newHeight)
    }

    /**
     * 将长宽为width和height的矩形，放大为长宽比为ratio的矩形
     */
    fun enlarge(width: Int, height: Int, expectRatio: Float): Pair<Int, Int> {
        val ratio = width / height.toFloat()
        if (ratio > expectRatio) {
            val newHeight = (width / expectRatio).toInt()
            return Pair(width, newHeight)
        }
        val newWidth = (height * expectRatio).toInt()
        return Pair(newWidth, height)
    }

    fun rangeCrop(value: Int, min: Int, max: Int): Pair<Int, Float> {
        if (value < min) {
            return Pair(min, min.toFloat() / value)
        }
        else if (value > max) {
            return Pair(max, max.toFloat() / value)
        }
        return Pair(value, 1.0f)
    }
}