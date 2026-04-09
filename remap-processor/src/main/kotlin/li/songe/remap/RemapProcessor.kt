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
            results.add(Triple(typeElement, toClassName, ArrayList()))
        }
        roundEnv.getElementsAnnotatedWith(methodAnnElement).forEach { methodElement ->
            methodElement as ExecutableElement
            val toMethodName = findAnnotationValue(methodElement, methodAnnElement)
            val pair = methodElement to toMethodName
            val parent = methodElement.enclosingElement as TypeElement
            val list = results.find { it.first == parent }?.third
            if (list != null) {
                if (list.any { it.first.simpleName.contentEquals(methodElement.simpleName) }) {
                    processingEnv.messager.printMessage(
                        Diagnostic.Kind.ERROR,
                        "the method ${methodElement.simpleName} by RemapMethod annotated should not supported overload"
                    )
                    return true
                }
                list.add(pair)
            } else {
                results.add(Triple(parent, null, mutableListOf(pair)))
            }
        }
        results.forEach { (typeElement, toClassName, methods) ->
            processUnit(typeElement, toClassName, methods)
        }
        return true
    }


    private fun processUnit(
        typeElement: TypeElement,
        toClassName: String?,
        methods: List<Pair<ExecutableElement, String>>?,
    ) {
        val fromClassName = parseClassName(typeElement)

        val metadataName = getMetaClassName(fromClassName)
        val metadataWriter = ClassWriter(0).apply {
            visit(
                Opcodes.V1_8,
                Opcodes.ACC_FINAL or Opcodes.ACC_PRIVATE or Opcodes.ACC_SUPER,
                metadataName.replace('.', '/'),
                null,
                Type.getInternalName(Any::class.java),
                null,
            )
            if (toClassName != null) {
                visitAnnotation(toDescriptor(buildTypeName(toClassName)), false).visitEnd()
            }
            methods?.forEach { (methodElement, toMethodName) ->
                val fromMethodName = methodElement.simpleName.toString()
                visitAnnotation(toDescriptor(buildMethodName(fromMethodName, toMethodName)), false).visitEnd()
            }
            visitEnd()
        }
        val metadataFile = processingEnv.filer.createClassFile(metadataName, typeElement)
        metadataFile.openOutputStream().use {
            it.write(metadataWriter.toByteArray())
        }

        if (toClassName != null) {
            typeElement.enclosedElements.forEach { enclosedElement ->
                if (enclosedElement is TypeElement && enclosedElement.getAnnotation(RemapType::class.java) != null) {
                    processUnit(enclosedElement, toClassName + "$" + enclosedElement.simpleName, null)
                }
            }
        }
    }

    private fun getTypeElement(clazz: KClass<*>): TypeElement {
        return processingEnv.elementUtils.getTypeElement(clazz.java.name)!!
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
