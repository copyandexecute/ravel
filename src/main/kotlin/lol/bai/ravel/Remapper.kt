package lol.bai.ravel

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.intellij.psi.*
import com.intellij.psi.codeStyle.JavaCodeStyleManager
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import net.fabricmc.mappingio.tree.MappingTree.*

typealias Mappings = List<Mapping>

data class RemapperModel(
    val mappings: MutableList<Mapping> = arrayListOf(),
    val modules: MutableList<Module> = arrayListOf(),
)

private fun Mappings.newName(cls: ClassMapping): String? {
    var className = cls.srcName

    for (m in this) {
        val mClass = m.tree.getClass(className) ?: return null
        className = mClass.getName(m.dest)
    }

    return if (className == cls.srcName) null else className.replace("/", ".")
}

private fun Mappings.newName(field: FieldMapping): String? {
    var className = field.owner.srcName
    var fieldName = field.srcName

    for (m in this) {
        val mClass = m.tree.getClass(className) ?: return null
        val mField = mClass.getField(fieldName, null) ?: return null
        className = mClass.getName(m.dest)
        fieldName = mField.getName(m.dest)
    }

    return if (fieldName == field.srcName) null else fieldName
}

private fun Mappings.newName(method: MethodMapping): String? {
    var className = method.owner.srcName
    var methodName = method.srcName
    var methodDesc = method.srcDesc

    for (m in this) {
        val mClass = m.tree.getClass(className) ?: return null
        val mMethod = mClass.getMethod(methodName, methodDesc) ?: return null
        className = mClass.getName(m.dest)
        methodName = mMethod.getName(m.dest)
        methodDesc = mMethod.getDesc(m.dest)
    }

    return if (methodName == method.srcName) null else methodName
}

private fun forEachRefs(module: Module, elt: PsiElement, action: (PsiReference) -> Unit) {
    val app = ApplicationManager.getApplication()
    val refs = app.runReadAction(Computable {
        ReferencesSearch.search(elt, module.moduleScope).findAll()
    })

    WriteCommandAction.runWriteCommandAction(module.project, "Ravel Remapper", null, {
        refs.forEach {
            try {
                action(it)
            } catch (_: Exception) {
            }
        }
    })
}

/**
 * TODO: Currently tested with WTHIT api module
 *  - currently fail after one remap per file
 *  - static function seem busted, see WailaConstant#id
 *  - generics, see IRegistryFilter
 *  - shorten references
 *  - kotlin
 *  - how to ignore unused references from being searched
 */
fun remap(project: Project, model: RemapperModel) {
    val java = JavaPsiFacade.getInstance(project)
    val javaFactory = java.elementFactory
    val javaStyle = JavaCodeStyleManager.getInstance(project)

    for (module in model.modules) {
        for (mClass in model.mappings.first().tree.classes) {
            val pClass = java.findClass(mClass.srcName.replace("/", "."), module.getModuleWithDependenciesAndLibrariesScope(true)) ?: continue

            for (mField in mClass.fields) {
                val pField = pClass.findFieldByName(mField.srcName, false) ?: continue
                val newName = model.mappings.newName(mField) ?: continue

                forEachRefs(module, pField) ref@{ ref ->
                    val elt = ref.element

                    // Java: PsiReferenceExpression like qualifier.oldField or simple oldField
                    val refExpr = PsiTreeUtil.getParentOfType(elt, PsiReferenceExpression::class.java, false)
                    if (refExpr != null) {
                        val qualifier = refExpr.qualifierExpression
                        val replacementText =
                            if (qualifier != null) "${qualifier.text}.${newName}"
                            else newName

                        val newExpr = javaFactory.createExpressionFromText(replacementText, refExpr)
                        val replaced = refExpr.replace(newExpr)
                        javaStyle.shortenClassReferences(replaced, JavaCodeStyleManager.INCOMPLETE_CODE)
                        return@ref
                    }

                    // Java: references in code reference elements (e.g., static import) – replace import texts separately if desired
                    val codeRef = PsiTreeUtil.getParentOfType(elt, PsiJavaCodeReferenceElement::class.java, false)
                    if (codeRef != null) {
                        val newExpr = javaFactory.createExpressionFromText(
                            "${pField.containingClass?.qualifiedName}.${newName}",
                            codeRef
                        )
                        val replaced = codeRef.replace(newExpr)
                        javaStyle.shortenClassReferences(replaced, JavaCodeStyleManager.INCOMPLETE_CODE)
                        return@ref
                    }
                }
            }

            for (mMethod in mClass.methods) {
                val newName = model.mappings.newName(mMethod) ?: continue
                val (mParams, mReturn) = parseJvmDescriptor(mMethod.srcDesc!!)

                method@ for (pMethod in pClass.findMethodsByName(mMethod.srcName, false)) {
                    val pReturn = pMethod.returnType ?: PsiTypes.voidType()
                    if (pReturn.toRaw() != mReturn) continue

                    val pParams = pMethod.parameterList.parameters
                    if (pParams.size != mParams.size) continue
                    for (i in pParams.indices) {
                        val pParam = pParams[i]
                        val mParam = mParams[i]
                        if (pParam.type.toRaw() != mParam) continue@method
                    }

                    forEachRefs(module, pMethod) ref@{ ref ->
                        val elt = ref.element

                        // Java: method call expression
                        val call = PsiTreeUtil.getParentOfType(elt, PsiMethodCallExpression::class.java, false)
                        if (call != null) {
                            val argsText = call.argumentList.text // includes parentheses
                            val replacementText = "${newName}${argsText}"
                            val newExpr = javaFactory.createExpressionFromText(replacementText, call)
                            val replaced = call.replace(newExpr)
                            javaStyle.shortenClassReferences(replaced, JavaCodeStyleManager.INCOMPLETE_CODE)
                            return@ref
                        }

                        // Java: reference expression (method reference or qualifier)
                        val refExpr = PsiTreeUtil.getParentOfType(elt, PsiReferenceExpression::class.java, false)
                        if (refExpr != null) {
                            val parent = refExpr.parent
                            if (parent is PsiMethodCallExpression) {
                                // handled above
                            } else {
                                // method reference or qualifier — replace name or create qualified call
                                val newExpr = javaFactory.createExpressionFromText("${newName}()", refExpr)
                                val replaced = refExpr.replace(newExpr)
                                javaStyle.shortenClassReferences(replaced, JavaCodeStyleManager.INCOMPLETE_CODE)
                            }
                            return@ref
                        }
                    }
                }
            }

            val newName = model.mappings.newName(mClass) ?: continue
            forEachRefs(module, pClass) ref@{ ref ->
                val elt = ref.element

                // Java: replace Java code reference elements
                val javaRef = PsiTreeUtil.getParentOfType(elt, PsiJavaCodeReferenceElement::class.java, false)
                if (javaRef != null) {
                    val replaced = javaRef.replace(javaFactory.createExpressionFromText(newName, javaRef))
                    javaStyle.shortenClassReferences(replaced, JavaCodeStyleManager.INCOMPLETE_CODE)
                    return@ref
                }

                // Java: reference expressions (qualifier/identifier)
                val refExpr = PsiTreeUtil.getParentOfType(elt, PsiReferenceExpression::class.java, false)
                if (refExpr != null) {
                    val replaced = refExpr.replace(javaFactory.createExpressionFromText(newName, refExpr))
                    javaStyle.shortenClassReferences(replaced, JavaCodeStyleManager.INCOMPLETE_CODE)
                    return@ref
                }
            }
        }
    }
}

private fun PsiType.toRaw(): String {
    return when (this) {
        is PsiArrayType -> componentType.toRaw() + "[]"
        is PsiPrimitiveType -> name
        else -> {
            val ret = canonicalText
            if (ret.contains("<")) ret.substringBefore("<") else ret
        }
    }
}

private fun parseJvmDescriptor(descriptor: String): Pair<List<String>, String> {
    var i = 0
    fun parseType(): String {
        return when (val c = descriptor[i++]) {
            'B' -> "byte"
            'C' -> "char"
            'D' -> "double"
            'F' -> "float"
            'I' -> "int"
            'J' -> "long"
            'S' -> "short"
            'Z' -> "boolean"
            'V' -> "void"
            '[' -> {
                // array: parse component type and append []
                val comp = parseType()
                "$comp[]"
            }

            'L' -> {
                // object: read until ';'
                val start = i
                val semicolon = descriptor.indexOf(';', start)
                val internal = descriptor.substring(start, semicolon)
                i = semicolon + 1
                internal.replace('/', '.')
            }

            else -> throw IllegalArgumentException("Unknown descriptor char: $c in $descriptor")
        }
    }

    if (descriptor.isEmpty() || descriptor[0] != '(') throw IllegalArgumentException("Bad descriptor: $descriptor")
    i = 1
    val params = mutableListOf<String>()
    while (descriptor[i] != ')') params += parseType()
    i++ // skip ')'
    val ret = parseType()
    return params to ret
}
