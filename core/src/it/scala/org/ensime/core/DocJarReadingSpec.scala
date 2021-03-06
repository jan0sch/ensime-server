// Copyright: 2010 - 2018 https://github.com/ensime/ensime-server/graphs
// License: http://www.gnu.org/licenses/gpl-3.0.en.html
package org.ensime.core

import org.ensime.fixture._
import org.ensime.util.EnsimeSpec

class DocJarReadingSpec extends EnsimeSpec with SharedEnsimeConfigFixture {

  val original = EnsimeConfigFixture.DocsTestProject

  "DocJarReading" should "serve entries from jar files" in withEnsimeConfig {
    c =>
      val reader = DocJarReading.forConfig(c)

      val content =
        reader.docJarContent("scala-library-" + c.scalaVersion + "-javadoc.jar",
                             "index.html")

      content should not be 'empty
  }
}
