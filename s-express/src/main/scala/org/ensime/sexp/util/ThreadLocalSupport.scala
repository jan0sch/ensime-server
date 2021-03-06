// Copyright: 2010 - 2018 https://github.com/ensime/ensime-server/graphs
// License: http://www.gnu.org/licenses/lgpl-3.0.en.html
package org.ensime.sexp.util

trait ThreadLocalSupport {
  protected def local[T](t: => T) = new ThreadLocal[T] {
    override def initialValue = t
  }
}
