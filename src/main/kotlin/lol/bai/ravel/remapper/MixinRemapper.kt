package lol.bai.ravel.remapper

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import lol.bai.ravel.decapitalize
import net.fabricmc.mappingio.tree.MappingTree.MethodMapping

// TODO:
//  - verify ambiguous result when remapping injection target
//  - mixin extras
@Suppress("ConstPropertyName")
object MixinRemapper : Remapper<PsiJavaFile>("java", { it as? PsiJavaFile }) {

    val rawClassRegex = Regex("L([A-Za-z_$][A-Za-z0-9_$]*(?:/[A-Za-z_$][A-Za-z0-9_$]*)*);")

    // @formatter:off
    const val mixin          = "org.spongepowered.asm.mixin"
    const val Mixin          = "${mixin}.Mixin"
    const val Invoker        = "${mixin}.gen.Invoker"
    const val Accessor       = "${mixin}.gen.Accessor"
    const val At             = "${mixin}.injection.At"
    const val Inject         = "${mixin}.injection.Inject"
    const val ModifyArg      = "${mixin}.injection.ModifyArg"
    const val ModifyArgs     = "${mixin}.injection.ModifyArgs"
    const val ModifyConstant = "${mixin}.injection.ModifyConstant"
    const val ModifyVariable = "${mixin}.injection.ModifyVariable"
    const val Redirect       = "${mixin}.injection.Redirect"
    // @formatter:on

    val INJECTS = setOf(Inject, ModifyArg, ModifyArgs, ModifyConstant, ModifyVariable, Redirect)

    object InjectionPoint {
        // @formatter:off
        const val HEAD          = "HEAD"
        const val RETURN        = "RETURN"
        const val TAIL          = "TAIL"
        const val INVOKE        = "INVOKE"
        const val INVOKE_ASSIGN = "INVOKE_ASSIGN"
        const val FIELD         = "FIELD"
        const val NEW           = "NEW"
        const val INVOKE_STRING = "INVOKE_STRING"
        const val JUMP          = "JUMP"
        const val CONSTANT      = "CONSTANT"
        const val STORE         = "STORE"
        // @formatter:on

        val ALL = setOf(HEAD, RETURN, TAIL, INVOKE, INVOKE_ASSIGN, FIELD, NEW, INVOKE_STRING, JUMP, CONSTANT, STORE)
        val INVOKES = setOf(INVOKE, INVOKE_ASSIGN, INVOKE_STRING)
    }

    fun isRemapped(pAnnotation: PsiAnnotation): Boolean {
        val pRemap = pAnnotation.findAttributeValue("remap") ?: return true
        pRemap as PsiLiteralExpression
        return pRemap.value as Boolean
    }

    fun remapDesc(desc: String, mappings: Mappings, mClasses: ClassMappings): String {
        return desc.replace(rawClassRegex) m@{ match ->
            val className = replaceAllQualifier(match.groupValues[1])
            val mClass = mClasses[className] ?: return@m match.value
            val newClassName = mappings.map(mClass) ?: return@m match.value
            "L${newClassName};"
        }
    }

    override fun remap(project: Project, mappings: Mappings, mClasses: ClassMappings, pFile: PsiJavaFile, writers: Writers) {
        val psi = JavaPsiFacade.getInstance(project).elementFactory
        val mixinTargets = hashMapOf<String, String>()

        pFile.process r@{ pAnnotation: PsiAnnotation ->
            val pClass = pAnnotation.parent<PsiClass>() ?: return@r
            val className = pClass.qualifiedName ?: return@r
            val annotationName = pAnnotation.qualifiedName ?: return@r

            if (annotationName == Mixin) {
                if (!isRemapped(pAnnotation)) return@r

                fun remapTarget(pTarget: PsiLiteralExpression) {
                    val target = pTarget.value as String
                    val mTargetClass = mClasses[replaceAllQualifier(target)] ?: return
                    var newTarget = mappings.map(mTargetClass) ?: return
                    newTarget = replacePkgQualifier(newTarget)
                    writers.add { pTarget.replace(psi.createExpressionFromText("\"${newTarget}\"", pTarget)) }
                }

                val pTargets = pAnnotation.findDeclaredAttributeValue("targets")
                if (pTargets != null) when (pTargets) {
                    is PsiLiteralExpression -> {
                        val value = pTargets.value as String
                        remapTarget(pTargets)
                        mixinTargets[className] = replaceAllQualifier(value)
                    }

                    is PsiArrayInitializerMemberValue -> pTargets.initializers.forEach {
                        if (it is PsiLiteralExpression) remapTarget(it) else {
                            writers.add {
                                val pComment = psi.createCommentFromText("TODO(Ravel): target not a literal or array of literals", pClass)
                                pClass.addBefore(pComment, pClass.firstChild)
                            }
                            thisLogger().warn("$className: target not a literal or array of literals")
                        }
                    }

                    else -> {
                        writers.add {
                            val pComment = psi.createCommentFromText("TODO(Ravel): target not a literal or array of literals", pClass)
                            pClass.addBefore(pComment, pClass.firstChild)
                        }
                        thisLogger().warn("$className: target not a literal or array of literals")
                    }
                }

                val pValues = pAnnotation.findDeclaredAttributeValue("value")
                if (pValues != null) {
                    if (pValues is PsiClassObjectAccessExpression) {
                        mixinTargets[className] = pValues.operand.type.canonicalText
                    }
                }

                return@r
            }

            if (annotationName == Invoker) {
                if (!isRemapped(pAnnotation)) return@r
                val pMethod = pAnnotation.parent<PsiMethod>() ?: return@r
                val methodName = pMethod.name

                val targetClassName = mixinTargets[className]
                if (targetClassName == null) {
                    writers.add {
                        val pComment = psi.createCommentFromText("TODO(Ravel): Could not determine a single target", pMethod)
                        pMethod.addBefore(pComment, pMethod.firstChild)
                    }
                    thisLogger().warn("$className#$methodName: Could not determine a single target")
                    return@r
                }
                val mTargetClass = mClasses[targetClassName] ?: return@r

                var targetSignature: String? = null
                var targetMethodName = when {
                    methodName.startsWith("call") -> methodName.removePrefix("call").decapitalize()
                    methodName.startsWith("invoke") -> methodName.removePrefix("invoke").decapitalize()
                    else -> null
                }

                val pValue = pAnnotation.findDeclaredAttributeValue("value")
                if (pValue is PsiLiteralExpression) {
                    val value = pValue.value as String
                    if (value == "<init>") return@r
                    if (value.contains('(')) {
                        targetMethodName = value.substringBefore('(')
                        targetSignature = value.removePrefix(targetMethodName)
                    } else {
                        targetMethodName = value
                    }
                }

                if (targetMethodName == null) {
                    writers.add {
                        val pComment = psi.createCommentFromText("TODO(Ravel): No target method", pMethod)
                        pMethod.addBefore(pComment, pMethod.firstChild)
                    }
                    thisLogger().warn("$className#$methodName: No target method")
                    return@r
                }

                if (targetSignature == null) targetSignature = JavaRemapper.signature(pMethod)

                val mTargetMethod = mTargetClass.getMethod(targetMethodName, targetSignature) ?: return@r
                val newMethodName = mappings.map(mTargetMethod) ?: return@r
                writers.add {
                    pAnnotation.setDeclaredAttributeValue("value", psi.createExpressionFromText("\"${newMethodName}\"", pAnnotation))
                }
                return@r
            }

            if (annotationName == Accessor) {
                if (!isRemapped(pAnnotation)) return@r
                val pMethod = pAnnotation.parent<PsiMethod>() ?: return@r
                val methodName = pMethod.name

                val targetClassName = mixinTargets[className]
                if (targetClassName == null) {
                    writers.add {
                        val pComment = psi.createCommentFromText("TODO(Ravel): Could not determine a single target", pMethod)
                        pMethod.addBefore(pComment, pMethod.firstChild)
                    }
                    thisLogger().warn("$className#$methodName: Could not determine a single target")
                    return@r
                }
                val mTargetClass = mClasses[targetClassName] ?: return@r

                var targetFieldName = when {
                    methodName.startsWith("get") -> methodName.removePrefix("get").decapitalize()
                    methodName.startsWith("set") -> methodName.removePrefix("set").decapitalize()
                    methodName.startsWith("is") -> methodName.removePrefix("is").decapitalize()
                    else -> null
                }

                val pValue = pAnnotation.findDeclaredAttributeValue("value")
                if (pValue is PsiLiteralExpression) targetFieldName = pValue.value as String

                if (targetFieldName == null) {
                    writers.add {
                        val pComment = psi.createCommentFromText("TODO(Ravel): No target field", pMethod)
                        pMethod.addBefore(pComment, pMethod.firstChild)
                    }
                    thisLogger().warn("$className#$methodName: No target field")
                    return@r
                }

                val mTargetField = mTargetClass.getField(targetFieldName, null) ?: return@r
                val newFieldName = mappings.map(mTargetField) ?: return@r
                writers.add {
                    pAnnotation.setDeclaredAttributeValue("value", psi.createExpressionFromText("\"${newFieldName}\"", null))
                }
                return@r
            }

            if (INJECTS.contains(annotationName)) {
                if (!isRemapped(pAnnotation)) return@r
                val pMethod = pAnnotation.parent<PsiMethod>() ?: return@r
                val methodName = pMethod.name

                val pDesc = pAnnotation.findDeclaredAttributeValue("target")
                if (pDesc != null) {
                    writers.add {
                        val pComment = psi.createCommentFromText("TODO(Ravel): target desc is not supported", pMethod)
                        pMethod.addBefore(pComment, pMethod.firstChild)
                    }
                    thisLogger().warn("$className#$methodName: target desc is not supported")
                    return@r
                }

                fun remapTargetMethod(pTarget: PsiLiteralExpression) {
                    val targetClassName = mixinTargets[className]
                    if (targetClassName == null) {
                        writers.add {
                            val pComment = psi.createCommentFromText("TODO(Ravel): Could not determine a single target", pMethod)
                            pMethod.addBefore(pComment, pMethod.firstChild)
                        }
                        thisLogger().warn("$className#$methodName: Could not determine a single target")
                        return
                    }

                    val targetMethod = pTarget.value as String
                    if (targetMethod.contains('*') || targetMethod.contains(' ')) {
                        writers.add {
                            val pComment = psi.createCommentFromText("TODO(Ravel): wildcard and target are not supported", pMethod)
                            pMethod.addBefore(pComment, pMethod.firstChild)
                        }
                        thisLogger().warn("$className#$methodName: wildcard and regex target are not supported")
                        return
                    }

                    val targetMethodAndDesc = if (targetMethod.startsWith('L')) targetMethod.substringAfter(';') else targetMethod
                    val targetMethodName = targetMethodAndDesc.substringBefore('(')
                    val targetMethodDesc = targetMethodAndDesc.removePrefix(targetMethodName)

                    var newTargetMethodName = targetMethod
                    if (mClasses.contains(targetClassName)) {
                        val mClass = mClasses[targetClassName]!!

                        if (targetMethodDesc.isNotEmpty()) {
                            val mMethod = mClass.getMethod(targetMethodName, targetMethodDesc)
                            if (mMethod != null) {
                                newTargetMethodName = mappings.map(mMethod) ?: targetMethodName
                            }
                        } else {
                            var mMethod: MethodMapping? = null
                            for (it in mClass.methods) {
                                if (it.srcName != targetMethodName) continue
                                if (mMethod != null) {
                                    writers.add {
                                        val pComment = psi.createCommentFromText("TODO(Ravel): target method is ambiguous", pMethod)
                                        pMethod.addBefore(pComment, pMethod.firstChild)
                                    }
                                    thisLogger().warn("$className#$methodName: target method is ambiguous")
                                    return
                                }
                                mMethod = it
                            }
                            if (mMethod != null) newTargetMethodName = mappings.map(mMethod) ?: targetMethodName
                        }
                    }

                    val newTargetMethodDesc = remapDesc(targetMethodDesc, mappings, mClasses)
                    val newTarget = "\"${newTargetMethodName}${newTargetMethodDesc}\""

                    writers.add { pTarget.replace(psi.createExpressionFromText(newTarget, pTarget)) }
                }

                val pTargetMethods = pAnnotation.findDeclaredAttributeValue("method") ?: return@r
                when (pTargetMethods) {
                    is PsiLiteralExpression -> remapTargetMethod(pTargetMethods)
                    is PsiArrayInitializerMemberValue -> pTargetMethods.initializers.forEach {
                        if (it is PsiLiteralExpression) remapTargetMethod(it) else {
                            writers.add {
                                val pComment = psi.createCommentFromText("TODO(Ravel): target not a literal or array of literals", pMethod)
                                pMethod.addBefore(pComment, pMethod.firstChild)
                            }
                            thisLogger().warn("$className: target not a literal or array of literals")
                        }
                    }

                    else -> {
                        writers.add {
                            val pComment = psi.createCommentFromText("TODO(Ravel): target not a literal or array of literals", pMethod)
                            pMethod.addBefore(pComment, pMethod.firstChild)
                        }
                        thisLogger().warn("$className: target not a literal or array of literals")
                    }
                }
                return@r
            }

            if (annotationName == At) {
                if (!isRemapped(pAnnotation)) return@r
                val pMethod = pAnnotation.parent<PsiMethod>() ?: return@r
                val methodName = pMethod.name

                val pPoint = pAnnotation.findDeclaredAttributeValue("value") ?: return@r
                pPoint as PsiLiteralExpression
                val point = pPoint.value as String

                if (!InjectionPoint.ALL.contains(point)) {
                    writers.add {
                        val pComment = psi.createCommentFromText("TODO(Ravel): Unknown injection point $point", pMethod)
                        pMethod.addBefore(pComment, pMethod.firstChild)
                    }
                    thisLogger().warn("$className#$methodName: Unknown injection point $point")
                    return@r
                }

                val pDesc = pAnnotation.findDeclaredAttributeValue("desc")
                if (pDesc != null) {
                    writers.add {
                        val pComment = psi.createCommentFromText("TODO(Ravel): @At.desc is not supported", pMethod)
                        pMethod.addBefore(pComment, pMethod.firstChild)
                    }
                    thisLogger().warn("$className#$methodName: @At.desc is not supported")
                }

                val pArgs = pAnnotation.findDeclaredAttributeValue("args")
                if (pArgs != null) {
                    writers.add {
                        val pComment = psi.createCommentFromText("TODO(Ravel): @At.args is not supported", pMethod)
                        pMethod.addBefore(pComment, pMethod.firstChild)
                    }
                    thisLogger().warn("$className#$methodName: @At.args is not supported")
                }

                if (point == InjectionPoint.FIELD) {
                    val pTarget = pAnnotation.findDeclaredAttributeValue("target") ?: return@r
                    pTarget as PsiLiteralExpression
                    val target = pTarget.value as String

                    if (target.contains('*')) {
                        writers.add {
                            val pComment = psi.createCommentFromText("TODO(Ravel): wildcard @At.target is not supported", pMethod)
                            pMethod.addBefore(pComment, pMethod.firstChild)
                        }
                        thisLogger().warn("$className#$methodName: wildcard @At.target is not supported")
                        return@r
                    }

                    val targetHasClassName = target.startsWith('L')
                    val targetClassName =
                        if (targetHasClassName) replaceAllQualifier(target.removePrefix("L").substringBefore(';'))
                        else mixinTargets[className]
                    if (targetClassName == null) {
                        writers.add {
                            val pComment = psi.createCommentFromText("TODO(Ravel): Could not determine @At.target field owner", pMethod)
                            pMethod.addBefore(pComment, pMethod.firstChild)
                        }
                        thisLogger().warn("$className#$methodName: Could not determine @At.target field owner")
                        return@r
                    }

                    val targetFieldAndDesc = if (targetHasClassName) target.substringAfter(';') else target
                    val targetFieldName = targetFieldAndDesc.substringBefore(':')
                    val targetFieldDesc = targetFieldAndDesc.substringAfter(':', "")

                    var newTargetClassName = targetClassName
                    var newTargetFieldName = targetFieldName
                    if (mClasses.contains(targetClassName)) {
                        val mClass = mClasses[targetClassName]!!
                        newTargetClassName = mappings.map(mClass) ?: targetClassName
                        val mField = mClass.getField(targetFieldName, null)
                        if (mField != null) {
                            newTargetFieldName = mappings.map(mField) ?: targetFieldName
                        }
                    }

                    val newTargetFieldDesc = remapDesc(targetFieldDesc, mappings, mClasses)
                    val newTarget = "\"L${newTargetClassName};${newTargetFieldName}:${newTargetFieldDesc}\""

                    writers.add { pAnnotation.setDeclaredAttributeValue("target", psi.createExpressionFromText(newTarget, pAnnotation)) }
                    return@r
                }

                if (InjectionPoint.INVOKES.contains(point)) {
                    val pTarget = pAnnotation.findDeclaredAttributeValue("target") ?: return@r
                    pTarget as PsiLiteralExpression
                    val target = pTarget.value as String

                    if (target.contains('*')) {
                        writers.add {
                            val pComment = psi.createCommentFromText("TODO(Ravel): wildcard @At.target is not supported", pMethod)
                            pMethod.addBefore(pComment, pMethod.firstChild)
                        }
                        thisLogger().warn("$className#$methodName: wildcard @At.target is not supported")
                        return@r
                    }

                    if (!target.contains('(')) {
                        writers.add {
                            val pComment = psi.createCommentFromText("TODO(Ravel): @At.target doesn't have a description", pMethod)
                            pMethod.addBefore(pComment, pMethod.firstChild)
                        }
                        thisLogger().warn("$className#$methodName: @At.target doesn't have a description")
                    }

                    val targetHasClassName = target.startsWith('L')
                    val targetClassName =
                        if (targetHasClassName) replaceAllQualifier(target.removePrefix("L").substringBefore(';'))
                        else mixinTargets[className]
                    if (targetClassName == null) {
                        writers.add {
                            val pComment = psi.createCommentFromText("TODO(Ravel): Could not determine @At.target method owner", pMethod)
                            pMethod.addBefore(pComment, pMethod.firstChild)
                        }
                        thisLogger().warn("$className#$methodName: Could not determine @At.target method owner")
                        return@r
                    }

                    val targetMethodAndDesc = if (targetHasClassName) target.substringAfter(';') else target
                    val targetMethodName = targetMethodAndDesc.substringBefore('(')
                    val targetMethodDesc = targetMethodAndDesc.removePrefix(targetMethodName)

                    var newTargetClassName = targetClassName
                    var newTargetMethodName = targetMethodName
                    if (mClasses.contains(targetClassName)) {
                        val mClass = mClasses[targetClassName]!!
                        newTargetClassName = mappings.map(mClass) ?: targetClassName
                        val mMethod = mClass.getMethod(targetMethodName, targetMethodDesc)
                        if (mMethod != null) {
                            newTargetMethodName = mappings.map(mMethod) ?: targetMethodName
                        }
                    }

                    val newTargetMethodDesc = remapDesc(targetMethodDesc, mappings, mClasses)
                    val newTarget = "\"L${newTargetClassName};${newTargetMethodName}${newTargetMethodDesc}\""

                    writers.add { pAnnotation.setDeclaredAttributeValue("target", psi.createExpressionFromText(newTarget, pAnnotation)) }
                    return@r
                }

                if (point == InjectionPoint.NEW) {
                    val pTarget = pAnnotation.findDeclaredAttributeValue("target") ?: return@r
                    pTarget as PsiLiteralExpression
                    val target = pTarget.value as String

                    val newTarget = if (target.startsWith('(')) remapDesc(target, mappings, mClasses) else {
                        val mClass = mClasses[replaceAllQualifier(replaceAllQualifier(target))] ?: return@r
                        mappings.map(mClass) ?: target
                    }

                    writers.add { pAnnotation.setDeclaredAttributeValue("target", psi.createExpressionFromText("\"${newTarget}\"", pAnnotation)) }
                    return@r
                }


                return@r
            }

            if (annotationName.startsWith(mixin)) {
                writers.add {
                    val pComment = psi.createCommentFromText("TODO(Ravel): unknown annotation $annotationName", pClass)
                    pClass.addBefore(pComment, pClass.firstChild)
                }
                thisLogger().warn("$className: unknown annotation $annotationName")
            }

            annotationName
        }
    }

}
