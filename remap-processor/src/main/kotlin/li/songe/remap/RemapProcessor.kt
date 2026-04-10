package li.songe.remap

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import javax.annotation.processing.AbstractProcessor
import javax.annotation.processing.RoundEnvironment
import javax.lang.model.SourceVersion
import javax.lang.model.element.Element
import javax.lang.model.element.ExecutableElement
import javax.lang.model.element.PackageElement
import javax.lang.model.element.TypeElement
import javax.lang.model.type.DeclaredType
import javax.tools.Diagnostic
import javax.tools.JavaFileObject
import kotlin.reflect.KClass

class RemapProcessor : AbstractProcessor() {
    override fun getSupportedSourceVersion(): SourceVersion = SourceVersion.latestSupported()
    override fun getSupportedAnnotationTypes() = setOf(RemapType::class.java.name, RemapMethod::class.java.name)
    override fun process(annotations: Set<TypeElement>, roundEnv: RoundEnvironment): Boolean {
        val typeAnnElement = getTypeElement(RemapType::class)
        val methodAnnElement = getTypeElement(RemapMethod::class)
        val results = mutableListOf<Triple<TypeElement, String?, MutableList<Pair<ExecutableElement, String>>>>()
        roundEnv.getElementsAnnotatedWith(typeAnnElement).forEach { typeElement ->
            typeElement as TypeElement
            val toClassName = findAnnotationValue(typeElement, typeAnnElement)
            if (parseClassName(typeElement) == toClassName) {
                printErrorMessage("the type $toClassName by RemapType parameter must be different type")
            }
            results.add(Triple(typeElement, toClassName, ArrayList()))
        }
        roundEnv.getElementsAnnotatedWith(methodAnnElement).forEach { methodElement ->
            methodElement as ExecutableElement
            val toMethodName = findAnnotationValue(methodElement, methodAnnElement)
            if (methodElement.simpleName.contentEquals(toMethodName)) {
                printErrorMessage("the method ${methodElement.simpleName} by RemapMethod parameter must be different name")
                return true
            }
            val pair = methodElement to toMethodName
            val parent = methodElement.enclosingElement as TypeElement
            val list = results.find { it.first == parent }?.third
            if (list != null) {
                if (list.any { it.first.simpleName.contentEquals(methodElement.simpleName) }) {
                    printErrorMessage("the method ${methodElement.simpleName} by RemapMethod annotated is not supported overload")
                    return true
                }
                list.add(pair)
            } else {
                results.add(Triple(parent, null, mutableListOf(pair)))
            }
        }
        val outputs = hashMapOf<String, Pair<ClassWriter, JavaFileObject>>()
        results.forEach { (typeElement, toClassName, methods) ->
            processUnit(outputs, typeElement, toClassName, methods)
        }
        outputs.values.forEach { (metadataWriter, metadataFile) ->
            metadataWriter.visitEnd()
            metadataFile.openOutputStream().use {
                it.write(metadataWriter.toByteArray())
            }
        }
        return true
    }

    private fun processUnit(
        outputs: MutableMap<String, Pair<ClassWriter, JavaFileObject>>,
        typeElement: TypeElement,
        toClassName: String?,
        methods: List<Pair<ExecutableElement, String>>?,
    ) {
        val fromClassName = parseClassName(typeElement)

        val metadataName = getMetaClassName(fromClassName)
        val (metadataWriter) = outputs.getOrPut(metadataName) {
            ClassWriter(0).apply {
                visit(
                    Opcodes.V1_8,
                    Opcodes.ACC_FINAL or Opcodes.ACC_PRIVATE or Opcodes.ACC_SUPER,
                    metadataName.replace('.', '/'),
                    null,
                    Type.getInternalName(Any::class.java),
                    null,
                )
            } to processingEnv.filer.createClassFile(metadataName, typeElement)
        }

        if (toClassName != null) {
            metadataWriter.visitAnnotation(toDescriptor(buildTypeName(toClassName)), false).visitEnd()
        }
        methods?.forEach { (methodElement, toMethodName) ->
            val fromMethodName = methodElement.simpleName.toString()
            metadataWriter.visitAnnotation(toDescriptor(buildMethodName(fromMethodName, toMethodName)), false)
                .visitEnd()
        }

        if (toClassName != null) {
            typeElement.enclosedElements.forEach { enclosedElement ->
                if (enclosedElement is TypeElement && enclosedElement.getAnnotation(RemapType::class.java) == null) {
                    processUnit(outputs, enclosedElement, toClassName + "$" + enclosedElement.simpleName, null)
                }
            }
        }
    }

    private fun getTypeElement(clazz: KClass<*>): TypeElement {
        return processingEnv.elementUtils.getTypeElement(clazz.java.name)!!
    }

    private fun printErrorMessage(message: String) {
        processingEnv.messager.printMessage(Diagnostic.Kind.ERROR, message)
    }

    companion object {
        private fun parseClassName(element: Element): String = when (val enclosing = element.enclosingElement) {
            is TypeElement -> parseClassName(enclosing) + "$" + element.simpleName
            is PackageElement -> enclosing.qualifiedName.toString() + "." + element.simpleName
            else -> element.simpleName.toString()
        }

        private fun findAnnotationValue(
            element: Element,
            annotationElement: TypeElement,
        ): String {
            val mirror = element.annotationMirrors.find { it.annotationType.asElement() == annotationElement }!!
            return when (val value = mirror.elementValues.values.single().value) {
                is String -> value // method
                is DeclaredType -> parseClassName(value.asElement()) // type
                else -> error("Unsupported annotation value $value")
            }
        }
    }
}
