package lol.bai.ravel.remapper

import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtPsiFactory

private val regex = Regex(".*\\.kt")

class KotlinRemapper : PsiRemapper<KtFile>(regex, { it as? KtFile? }) {

    private lateinit var factory: KtPsiFactory

    override fun init(): Boolean {
        if (!super.init()) return false
        this.factory = KtPsiFactory(project)
        return true
    }

    override fun comment(pElt: PsiElement, comment: String) {
        var pAnchor: PsiElement? = null
        comment.split('\n').forEach { line ->
            val pComment = factory.createComment("// $line")
            pAnchor =
                if (pAnchor == null) pElt.addBefore(pComment, pElt.firstChild)
                else pElt.addAfter(pComment, pAnchor)
        }
    }

    override fun remap() {
        TODO("Not yet implemented")
    }

}
