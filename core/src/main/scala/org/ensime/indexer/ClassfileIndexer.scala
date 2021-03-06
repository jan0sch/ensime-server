// Copyright: 2010 - 2018 https://github.com/ensime/ensime-server/graphs
// License: http://www.gnu.org/licenses/gpl-3.0.en.html
package org.ensime.indexer

import java.util

import scala.collection.JavaConverters._
import scala.collection.immutable.Queue
import scala.util._

import akka.event.slf4j.SLF4JLogging
import org.apache.commons.vfs2.FileObject
import org.objectweb.asm._
import org.objectweb.asm.Opcodes._

final class ClassfileIndexer(file: FileObject) extends SLF4JLogging {

  def indexClassfile(): RawClassfile = {
    val name = file.getName
    require(file.exists(), s"$name does not exist")
    require(name.getBaseName.endsWith(".class"), s"$name is not a class file")

    val in = file.getContent.getInputStream
    val raw = try {
      val reader   = new ClassReader(in)
      val receiver = new AsmCallback
      reader.accept(receiver, ClassReader.SKIP_FRAMES)
      receiver
    } finally in.close()

    raw.clazz
  }

  // extracts all the classnames from a descriptor
  private def classesInDescriptor(desc: String): List[ClassName] =
    DescriptorParser.parse(desc) match {
      case Descriptor(params, ret) =>
        (ret :: params).map {
          case c: ClassName       => c
          case a: ArrayDescriptor => a.reifier
        }
    }

  private class AsmCallback
      extends ClassVisitor(ASM5)
      with ReferenceInClassHunter {
    // updated every time we get more info
    @volatile var clazz: RawClassfile = _

    override def visit(
      version: Int,
      access: Int,
      name: String,
      signature: String,
      superName: String,
      interfaces: Array[String]
    ): Unit = {

      val signatureClass = if (signature != null && signature.nonEmpty) {
        Try(SignatureParser.parseGeneric(signature)) match {
          case Success(sig) => Some(sig)
          case Failure(t)   =>
            // WORKAROUND bad scalac plugins that produce dodgy classfiles
            // https://github.com/ensime/ensime-server/issues/1614
            log.warn(
              s"""Failed to parse '$file' signature for '$name'.
                 |Consider removing or fixing the offending binary.
                 |'$signature'""".stripMargin,
              t
            )
            None
        }
      } else {
        None
      }

      val interfaceNames: List[ClassName] =
        interfaces.map(ClassName.fromInternal)(collection.breakOut)
      val superClass = Option(superName).map(ClassName.fromInternal)

      internalRefs.addAll(
        interfaceNames.map(FullyQualifiedReference(_, None)).asJava
      )
      internalRefs.addAll(
        superClass.toList.map(FullyQualifiedReference(_, None)).asJava
      )

      clazz = RawClassfile(
        ClassName.fromInternal(name),
        signatureClass,
        Set.empty,
        superClass,
        interfaceNames,
        Access(access),
        (ACC_DEPRECATED & access) > 0,
        Nil,
        Queue.empty,
        RawSource(None, None),
        isScala = false,
        internalRefs.asScala.toList.distinct
      )
    }

    override def visitInnerClass(name: String,
                                 outerName: String,
                                 innerName: String,
                                 access: Int): Unit =
      if (outerName != null) {
        val outerClassName = ClassName.fromInternal(outerName)
        if (outerClassName == clazz.name) {
          clazz = clazz.copy(
            innerClasses = clazz.innerClasses + ClassName.fromInternal(name)
          )
        }
      }

    override def visitSource(filename: String, debug: String): Unit = {
      val isScala = filename != null && filename.endsWith(".scala")
      clazz = clazz.copy(source = RawSource(Option(filename), None),
                         isScala = isScala)
    }

    override def visitField(access: Int,
                            name: String,
                            desc: String,
                            signature: String,
                            value: AnyRef): FieldVisitor = {
      super.visitField(access, name, desc, signature, value)
      new FieldVisitor(ASM5) with ReferenceInFieldHunter {
        override def visitEnd(): Unit = {
          internalRefs.add(
            FullyQualifiedReference(ClassName.fromDescriptor(desc),
                                    clazz.source.line)
          )
          val field = RawField(
            FieldName(clazz.name, name),
            DescriptorParser.parseType(desc),
            Option(signature),
            Access(access),
            internalRefs.asScala.toList.distinct
          )
          clazz = clazz.copy(fields = field :: clazz.fields)
        }
      }
    }

    override def visitMethod(access: Int,
                             region: String,
                             desc: String,
                             signature: String,
                             exceptions: Array[String]): MethodVisitor = {
      super.visitMethod(access, region, desc, signature, exceptions)
      new MethodVisitor(ASM5) with ReferenceInMethodHunter {
        var firstLine: Option[Int] = None

        override def addRef(ref: FullyQualifiedName): Unit =
          internalRefs.add(FullyQualifiedReference(ref, Some(currentLine)))

        override def visitLineNumber(line: Int, start: Label): Unit = {
          val isEarliestLineSeen = firstLine.forall(_ < line)
          if (isEarliestLineSeen)
            firstLine = Some(line)
          currentLine = line
        }

        override def visitEnd(): Unit = {
          if (region == "<init>" || region == "<clinit>") {
            (clazz.source.line, firstLine) match {
              case (_, None)                                            =>
              case (Some(existing), Some(latest)) if existing <= latest =>
              case _ =>
                clazz = clazz.copy(source = clazz.source.copy(line = firstLine))
            }
          }
          if (exceptions != null) {
            addRefs(exceptions.map(ClassName.fromInternal))
          }
          addRefs(classesInDescriptor(desc))
          val descriptor = DescriptorParser.parse(desc)
          val method = RawMethod(
            MethodName(clazz.name, region, descriptor),
            Access(access),
            Option(signature),
            firstLine,
            internalRefs.asScala.toList.distinct
          )
          clazz = clazz.copy(methods = clazz.methods enqueue method)
        }
      }
    }
  }

  // factors out much of the verbose code that looks for references to members
  private trait ReferenceInClassHunter {
    this: ClassVisitor =>

    var clazz: RawClassfile

    var internalRefs = new util.LinkedList[FullyQualifiedReference]()

    private val annVisitor: AnnotationVisitor = new AnnotationVisitor(ASM5) {
      override def visitAnnotation(name: String, desc: String) = handleAnn(desc)
      override def visitEnum(
        name: String,
        desc: String,
        value: String
      ): Unit = handleAnn(desc)
    }

    private def handleAnn(desc: String): AnnotationVisitor = {
      clazz = clazz.copy(
        internalRefs = FullyQualifiedReference(
          ClassName.fromDescriptor(desc),
          clazz.source.line
        ) :: clazz.internalRefs
      )
      annVisitor
    }
    override def visitAnnotation(desc: String, visible: Boolean) =
      handleAnn(desc)
    override def visitTypeAnnotation(
      typeRef: Int,
      typePath: TypePath,
      desc: String,
      visible: Boolean
    ) = handleAnn(desc)
  }

  private trait ReferenceInFieldHunter {
    this: FieldVisitor =>

    protected var internalRefs = new util.LinkedList[FullyQualifiedReference]()

    private val annVisitor = new AnnotationVisitor(ASM5) {
      override def visitAnnotation(name: String,
                                   desc: String): AnnotationVisitor =
        handleAnn(desc)
    }
    private def handleAnn(desc: String): AnnotationVisitor = {
      internalRefs.add(
        FullyQualifiedReference(ClassName.fromDescriptor(desc), None)
      )
      annVisitor
    }
    override def visitTypeAnnotation(typeRef: Int,
                                     typePath: TypePath,
                                     desc: String,
                                     visible: Boolean): AnnotationVisitor =
      handleAnn(desc)
    override def visitAnnotation(desc: String,
                                 visible: Boolean): AnnotationVisitor =
      handleAnn(desc)
  }

  private trait ReferenceInMethodHunter {
    this: MethodVisitor =>

    protected var internalRefs     = new util.LinkedList[FullyQualifiedReference]()
    protected var currentLine: Int = _
    // doesn't disambiguate FQNs of methods, so storing as FieldName references
    private def memberOrInit(owner: String, name: String): FullyQualifiedName =
      name match {
        case "<init>" | "<clinit>" => ClassName.fromInternal(owner)
        case member                => FieldName(ClassName.fromInternal(owner), member)
      }

    protected def addRefs(refs: Seq[FullyQualifiedName]): Unit =
      refs.foreach(addRef)

    protected def addRef(ref: FullyQualifiedName): Unit =
      internalRefs.add(FullyQualifiedReference(ref, Some(currentLine)))

    override def visitLocalVariable(
      name: String,
      desc: String,
      signature: String,
      start: Label,
      end: Label,
      index: Int
    ): Unit =
      addRef(ClassName.fromDescriptor(desc))

    override def visitMultiANewArrayInsn(desc: String, dims: Int): Unit =
      addRef(ClassName.fromDescriptor(desc))

    override def visitTypeInsn(opcode: Int, desc: String): Unit =
      addRef(ClassName.fromInternal(desc))

    override def visitFieldInsn(
      opcode: Int,
      owner: String,
      name: String,
      desc: String
    ): Unit =
      addRef(memberOrInit(owner, name))

    override def visitTryCatchBlock(start: Label,
                                    end: Label,
                                    handler: Label,
                                    `type`: String): Unit =
      Option(`type`).foreach(desc => addRef(ClassName.fromInternal(desc)))

    override def visitMethodInsn(
      opcode: Int,
      owner: String,
      name: String,
      desc: String,
      itf: Boolean
    ): Unit = {
      val ownerName = ClassName.fromInternal(owner)
      addRef(ownerName)
      addRef(MethodName(ownerName, name, DescriptorParser.parse(desc)))
      addRefs(classesInDescriptor(desc))
    }

    override def visitInvokeDynamicInsn(name: String,
                                        desc: String,
                                        bsm: Handle,
                                        bsmArgs: AnyRef*): Unit = {
      addRef(memberOrInit(bsm.getOwner, bsm.getName))
      addRefs(classesInDescriptor(bsm.getDesc))
    }

    private val annVisitor: AnnotationVisitor = new AnnotationVisitor(ASM5) {
      override def visitAnnotation(name: String, desc: String) = handleAnn(desc)
      override def visitEnum(name: String, desc: String, value: String): Unit =
        handleAnn(desc)
    }
    private def handleAnn(desc: String): AnnotationVisitor = {
      addRef(ClassName.fromDescriptor(desc))
      annVisitor
    }
    override def visitAnnotation(desc: String, visible: Boolean) =
      handleAnn(desc)
    override def visitAnnotationDefault() = annVisitor
    override def visitInsnAnnotation(
      typeRef: Int,
      typePath: TypePath,
      desc: String,
      visible: Boolean
    ) = handleAnn(desc)
    override def visitLocalVariableAnnotation(
      typeRef: Int,
      typePath: TypePath,
      start: Array[Label],
      end: Array[Label],
      index: Array[Int],
      desc: String,
      visible: Boolean
    ) = handleAnn(desc)
    override def visitParameterAnnotation(
      parameter: Int,
      desc: String,
      visible: Boolean
    ) = handleAnn(desc)
    override def visitTryCatchAnnotation(
      typeRef: Int,
      typePath: TypePath,
      desc: String,
      visible: Boolean
    ) = handleAnn(desc)
    override def visitTypeAnnotation(
      typeRef: Int,
      typePath: TypePath,
      desc: String,
      visible: Boolean
    ) = handleAnn(desc)
  }
}
