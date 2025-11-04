package lol.bai.ravel.util

import com.intellij.openapi.util.Key
import com.intellij.openapi.util.UserDataHolder

typealias HolderKey<T> = Key<Holder<T?>>

data class Holder<T> (val value: T) {
    companion object {
        private val nullHolder = Holder(null)

        @Suppress("UNCHECKED_CAST")
        fun <T> ofNull(): Holder<T?> = nullHolder as Holder<T?>

        fun <T> ofNullable(value: T?): Holder<T?> {
            return if (value == null) ofNull() else Holder(value)
        }

        fun <T> key(key: String): HolderKey<T> {
            return Key.create<Holder<T?>>("lol.bai.ravel.${key}")
        }
    }
}

fun <T> HolderKey<T>.get(holder: UserDataHolder): Holder<T?>? {
    return holder.getUserData(this)
}

fun <T> HolderKey<T>.put(holder: UserDataHolder, value: T?): T? {
    holder.putUserData(this, Holder.ofNullable(value))
    return value
}
