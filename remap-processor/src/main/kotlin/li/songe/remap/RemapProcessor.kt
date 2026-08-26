package li.songe.remap

import javax.annotation.processing.AbstractProcessor
import javax.annotation.processing.RoundEnvironment
import javax.lang.model.SourceVersion
import javax.lang.model.element.Element
import javax.lang.model.element.ExecutableElement
import javax.lang.model.element.PackageElement
import javax.lang.model.element.TypeElement
import javax.lang.model.type.DeclaredType
import javax.tools.Diagnostic
import javax.tools.StandardLocation
import kotlin.reflect.KClass

class RemapProcessor : AbstractProcessor() {
    private val indexBuilder = RemapIndexBuilder()
    private var indexWritten = false

    override fun getSupportedSourceVersion(): SourceVersion = SourceVersion.latestSupported()
    override fun getSupportedAnnotationTypes() = setOf(RemapType::class.java.name, RemapMethod::class.java.name)
    override fun process(annotations: Set<TypeElement>, roundEnv: RoundEnvironment): Boolean {
        if (roundEnv.processingOver()) {
            writeIndex()
            return true
        }
        val typeAnnElement = getTypeElement(RemapType::class)
        val methodAnnElement = getTypeElement(RemapMethod::class)
        val results = mutableListOf<Triple<TypeElement, String?, MutableList<Pair<ExecutableElement, String>>>>()
        roundEnv.getElementsAnnotatedWith(typeAnnElement).forEach { typeElement ->
            typeElement as TypeElement
            val toTypeElement = findAnnotationTypeValue(typeElement, typeAnnElement)
            val toClassName = parseClassName(toTypeElement)
            if (parseClassName(typeElement) == toClassName) {
                printErrorMessage("the RemapType parameter of type ${typeElement.simpleName} can not use self", typeElement)
                return true
            }
            if (typeElement.kind.isInterface != toTypeElement.kind.isInterface) {
                printErrorMessage(
                    "the type ${typeElement.simpleName} annotated by RemapType must have the same kind " +
                        "(class or interface) as ${toTypeElement.simpleName}",
                    typeElement,
                )
                return true
            }
            results.add(Triple(typeElement, toClassName, ArrayList()))
        }
        roundEnv.getElementsAnnotatedWith(methodAnnElement).forEach { methodElement ->
            methodElement as ExecutableElement
            val toMethodName = findAnnotationStringValue(methodElement, methodAnnElement)
            if (!isValidJavaMethodName(toMethodName)) {
                printErrorMessage(
                    "the RemapMethod parameter of method ${methodElement.simpleName} must be a valid Java method name",
                    methodElement
                )
                return true
            }
            if (methodElement.simpleName.contentEquals(toMethodName)) {
                printErrorMessage(
                    "the RemapMethod parameter of method ${methodElement.simpleName} can not use self",
                    methodElement
                )
                return true
            }
            val parent = methodElement.enclosingElement as TypeElement
            val sameNameMethodCount = parent.enclosedElements.count {
                it is ExecutableElement && it.simpleName.contentEquals(methodElement.simpleName)
            }
            if (sameNameMethodCount > 1) {
                printErrorMessage(
                    "the method ${methodElement.simpleName} by RemapMethod annotated can not use overload",
                    methodElement
                )
                return true
            }
            val pair = methodElement to toMethodName
            val list = results.find { it.first == parent }?.third
            if (list != null) {
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
        val fromClassName = parseClassName(typeElement).toInternalName()

        if (toClassName != null) {
            indexBuilder.putType(fromClassName, toClassName.toInternalName())
        }
        methods?.forEach { (methodElement, toMethodName) ->
            val fromMethodName = methodElement.simpleName.toString()
            indexBuilder.putMethod(fromClassName, fromMethodName, toMethodName)
        }

        if (toClassName != null) {
            typeElement.enclosedElements.forEach { enclosedElement ->
                if (enclosedElement is TypeElement && enclosedElement.getAnnotation(RemapType::class.java) == null) {
                    processUnit(enclosedElement, toClassName + "$" + enclosedElement.simpleName, null)
                }
            }
        }
    }

    private fun writeIndex() {
        if (indexWritten) return
        indexWritten = true
        processingEnv.filer.createResource(
            StandardLocation.CLASS_OUTPUT,
            "",
            REMAP_INDEX_PATH,
        ).openOutputStream().bufferedWriter(Charsets.UTF_8).use {
            it.write(indexBuilder.build().encode())
        }
    }

    private fun getTypeElement(clazz: KClass<*>): TypeElement {
        return processingEnv.elementUtils.getTypeElement(clazz.java.name)!!
    }

    private fun printErrorMessage(message: String, element: Element) {
        processingEnv.messager.printMessage(Diagnostic.Kind.ERROR, message, element)
    }

    companion object {
        private fun String.toInternalName(): String = replace('.', '/')

        private fun parseClassName(element: Element): String = when (val enclosing = element.enclosingElement) {
            is TypeElement -> parseClassName(enclosing) + "$" + element.simpleName
            is PackageElement -> enclosing.qualifiedName.toString() + "." + element.simpleName
            else -> element.simpleName.toString()
        }

        private fun findAnnotationTypeValue(
            element: Element,
            annotationElement: TypeElement,
        ): TypeElement {
            val value = findAnnotationValue(element, annotationElement)
            return (value as DeclaredType).asElement() as TypeElement
        }

        private fun findAnnotationStringValue(
            element: Element,
            annotationElement: TypeElement,
        ): String = findAnnotationValue(element, annotationElement) as String

        private fun findAnnotationValue(
            element: Element,
            annotationElement: TypeElement,
        ): Any {
            val mirror = element.annotationMirrors.find { it.annotationType.asElement() == annotationElement }!!
            return mirror.elementValues.values.single().value
        }

        private fun isValidJavaMethodName(name: String): Boolean {
            return SourceVersion.isIdentifier(name) && !SourceVersion.isKeyword(name)
        }
    }
}
