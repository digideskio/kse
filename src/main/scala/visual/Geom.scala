// This file is distributed under the BSD 3-clause license.  See file LICENSE.
// Copyright (c) 2016 Rex Kerr and Calico Labs.

package kse.visual

import kse.maths._

case class Frame(origin: Vc, e: Vc) {
  def into(v: Vc, that: Frame): Vc = {
    val x = v.x.toDouble * e.x - v.y.toDouble * e.y + origin.x - that.origin.x
    val y = v.x.toDouble * e.y + v.y.toDouble * e.x + origin.y - that.origin.y
    Vc.from((x*that.e.x + y*that.e.y)/that.e.lenSq, (-x*that.e.y + y*that.e.x)/that.e.lenSq)
  }
}
object Frame {
  val natural = Frame(0 vc 0, 1 vc 0)
}

abstract class Xform { self =>
  def apply(v: Vc): Vc
  def revert(v: Vc): Vc
  def inverted = this match {
    case xi: Xform.Inverted => xi.original
    case _ => new Xform.Inverted(this)
  }
  def andThen(that: Xform): Xform = new Xform {
    def apply(v: Vc) = that(self(v))
    def revert(v: Vc) = self.revert(that.revert(v))
  }
}
object Xform {
  final class Inverted(val original: Xform) extends Xform {
    def apply(v: Vc) = original revert v
    def revert(v: Vc) = original apply v
  }
  sealed abstract class FromTo(val from: Frame, val to: Frame) extends Xform {
    override def inverted = this match {
      case xift: Xform.InvertedFromTo => xift.original
      case _ => new Xform.InvertedFromTo(this)
    }
  }
  final class InvertedFromTo(val original: FromTo) extends FromTo(original.to, original.from) {
    def apply(v: Vc) = original revert v
    def revert(v: Vc) = original apply v
  }
  final class Natural(from: Frame, to: Frame) extends FromTo(from, to) {
    def apply(v: Vc) = from into (v, to)
    def revert(v: Vc) = to into (v, from)
    override def inverted = new Natural(to, from)
  }

  val identity: Xform = new FromTo(Frame.natural, Frame.natural) {
    def apply(v: Vc) = v
    def revert(v: Vc) = v
    override def inverted = this
  }
  def natural(from: Frame, to: Frame): Xform = new Natural(from, to)
  def flipx(about: Float): Xform = new Xform {
    def apply(v: Vc) = Vc(about - v.x, v.y)
    def revert(v: Vc) = Vc(about - v.x, v.y)
  }
  def flipy(about: Float): Xform = new Xform {
    def apply(v: Vc) = Vc(v.x, about - v.y)
    def revert(v: Vc) = Vc(v.x, about - v.y)
  }
  def scale(scales: Vc): Xform = new Xform{
    def apply(v: Vc) = Vc(v.x * scales.x, v.y * scales.y)
    def revert(v: Vc) = Vc(v.x / scales.x, v.y / scales.y)
  }
  def shiftscale(shift: Vc, scale: Vc) = new Xform {
    def apply(v: Vc) = { val u = v - shift; Vc(u.x * scale.x, u.y * scale.y) }
    def revert(v: Vc) = { val u = Vc(v.x / scale.x, v.y / scale.y); u + shift }
  }
}

trait Xformable[X <: Xformable[_]] { def into(xform: Xform): X }
trait Boxed { def lu: Vc; def rb: Vc }
trait Centered extends Boxed { def center: Vc }
trait Enclosed extends Boxed { def encloses(v: Vc): Boolean }

case class Poly(points: Array[Long]) extends Boxed with Xformable[Poly] {
  def length = points.length
  def apply(i: Int) = Vc from points(i)
  def lu = {
    var p = Vc from points(0)
    var x = p.x
    var y = p.y
    var i = 1
    while (i < points.length) {
      p = Vc from points(i)
      x = math.min(x, p.x)
      y = math.min(x, p.y)
      i += 1
    }
    Vc(x, y)
  }
  def rb = {
    var p = Vc from points(0)
    var x = p.x
    var y = p.y
    var i = 1
    while (i < points.length) {
      p = Vc from points(i)
      x = math.max(x, p.x)
      y = math.max(x, p.y)
      i += 1
    }
    Vc(x, y)
  }
  def into(xform: Xform) = {
    val ps = new Array[Long](points.length)
    var i = 0
    while (i < points.length) {
      ps(i) = xform(Vc from points(i)).underlying
      i += 1
    }
    new Poly(ps)
  }
  override def toString = points.map(x => (Vc from x).toString).mkString("Poly(", " -> ", ")")
}
case class Circle(center: Vc, radius: Float) extends Centered with Enclosed with Xformable[Circle] {
  def encloses(v: Vc) = (v - center).lenSq <= radius
  def lu = center - radius
  def rb = center + radius
  def into(xform: Xform) = {
    val nc = xform(center)
    val cpdx = (xform(center - (radius, 0)) - xform(center + (radius, 0))).len
    val cpdy = (xform(center - (0, radius)) - xform(center + (0, radius))).len
    new Circle(nc, if (cpdx + cpdy <= 0) 0f else (cpdx*cpdy/(cpdx + cpdy)).toFloat)
  }
}
case class Rect(center: Vc, major: Vc, aspect: Float) extends Centered with Enclosed with Xformable[Rect] {
  def this(center: Vc, major: Float, minor: Float) = this(center, Vc(major, 0), minor/major)
  def this(center: Vc, radius: Float) = this(center, Vc(radius, 0), 1f)
  def encloses(v: Vc) = {
    val l2 = major.lenSq
    val rel = v - center
    (rel * major) <= l2 && (rel * major.ccw) <= l2*aspect*aspect
  }
  def lu = center - Vc(major.x.abs + aspect*major.y.abs, major.y.abs + aspect*major.x.abs)
  def rb = center + Vc(major.x.abs + aspect*major.y.abs, major.y.abs + aspect*major.x.abs)
  def into(xform: Xform) = {
    val nc = xform(center)
    val maj = (xform(center + major) - xform(center - major))
    val min = (xform(center + aspect*major.ccw) - xform(center - aspect*major.ccw))
    val nm =
      if (maj.lenSq > 0) {
        if (maj.lenSq*aspect*aspect closeTo min.lenSq) aspect
        else (min.lenSq/maj.lenSq).sqrt
      }
      else 0
    new Rect(nc, maj*0.5f, nm.toFloat)
  }
  def corners: Array[Long] = {
    val cs = new Array[Long](4)
    val minor = major.ccw*aspect
    cs(0) = (center + major + minor).underlying
    cs(1) = (center + major - minor).underlying
    cs(2) = (center - major - minor).underlying
    cs(3) = (center - major + minor).underlying
    cs
  }
}