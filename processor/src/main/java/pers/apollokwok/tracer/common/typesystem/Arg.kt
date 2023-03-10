package pers.apollokwok.tracer.common.typesystem

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSTypeParameter
import pers.apollokwok.ktutil.Bug
import pers.apollokwok.ktutil.lazyFast

internal sealed class Arg<T: Arg<T>>(val param: KSTypeParameter) : Convertible<Arg<*>>(){
    sealed class General<T: General<T>>(
        val type: Type<*>,
        param: KSTypeParameter
    ) :
        Arg<General<*>>(param)
    {
        final override fun convertAlias(): T = copy(type.convertAlias())
        final override fun convertStar(): T = copy(type.convertStar())

        // this is the core part
        final override fun convertGeneric(
            map: Map<String, Arg<*>>,
            fromAlias: Boolean,
        ): Pair<Arg<*>, Boolean> {
            val selfActualVarianceLabel =
                when (this) {
                    is In -> "in"
                    is Out -> "out"
                    is Simple -> param.variance.label
                }

            return if (type is Type.Generic) {
                val mappedArg = map[type.name]
                val convertedType by lazyFast { type.convertGeneric(map, fromAlias).first }
                val requireOut = !fromAlias && mappedArg !is Simple

                val newArg = when (mappedArg) {
                    // This condition would be removed after authority require typealias bounds.
                    is Star -> {
                        require(fromAlias)
                        Star(param)
                    }

                    is Out, null -> when (selfActualVarianceLabel) {
                        "" -> Out(convertedType, param)
                        "in" -> Star(param)
                        "out" -> copy(convertedType)
                        else -> Bug()
                    }

                    is In -> when (selfActualVarianceLabel) {
                        "" -> In(convertedType, param)
                        "in" -> copy(convertedType)
                        // change to require bound, but with a new map without current substitute arg.
                        "out" -> convertGeneric(map - type.name, fromAlias).first
                        else -> Bug()
                    }

                    is Simple -> copy(convertedType)
                }

                newArg to requireOut
            } else {
                val (convertedType, requireOut) = type.convertGeneric(map, fromAlias)
                val newArg = when {
                    !requireOut -> copy(type = convertedType)
                    selfActualVarianceLabel == "" -> Out(type = convertedType, param)
                    selfActualVarianceLabel == "in" -> Star(param)
                    selfActualVarianceLabel == "out" -> copy(type = convertedType)
                    else -> Bug()
                }
                newArg to requireOut
            }
        }

        //region fun copy
        @Suppress("UNCHECKED_CAST")
        fun copy(
            type: Type<*> = this.type,
            param: KSTypeParameter = this.param,
        ): T =
            when{
                type == this.type && param == this.param -> this
                this is Simple -> Simple(type, param)
                this is In -> In(type, param)
                this is Out -> Out(type, param)
                else -> Bug()
            } as T
        //endregion

        final override fun hashCode(): Int {
            return 31 * type.hashCode() + param.hashCode()
        }

        final override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (this.javaClass != other?.javaClass) return false

            other as General<*>

            if (type != other.type) return false
            if (param != other.param) return false

            return true
        }

        final override val allInnerKlasses: List<KSClassDeclaration> by
            lazyFast { type.allInnerKlasses }
    }

    class Simple(type: Type<*>, param: KSTypeParameter) : General<Simple>(type, param) {
        override fun toString(): String = "$type"

        override fun getContent(getPathImported: (KSClassDeclaration) -> Boolean): String? =
            type.getContent(getPathImported)

        override fun getName(isGross: Boolean, getSrcTag: (KSClassDeclaration) -> String?): String =
            type.getName(isGross, getSrcTag)
    }

    class In(type: Type<*>, param: KSTypeParameter) : General<In>(type, param) {
        override fun toString(): String = "in $type"

        override fun getContent(getPathImported: (KSClassDeclaration) -> Boolean): String? {
            val content = type.getContent(getPathImported)
            return when{
                content == null -> null
                type is Type.Compound -> content
                else -> "in $content"
            }
        }

        override fun getName(isGross: Boolean, getSrcTag: (KSClassDeclaration) -> String?): String =
            buildString {
                if (!isGross) append("???")
                append(type.getName(isGross, getSrcTag))
            }
    }

    class Out(type: Type<*>, param: KSTypeParameter) : General<Out>(type, param) {
        override fun toString(): String = "out $type"

        override fun getContent(getPathImported: (KSClassDeclaration) -> Boolean): String? {
            val content = type.getContent(getPathImported)
            return when{
                content == null -> null
                type is Type.Compound -> content
                else -> "out $content"
            }
        }

        override fun getName(isGross: Boolean, getSrcTag: (KSClassDeclaration) -> String?): String =
            buildString {
                if (!isGross) append("???")
                append(type.getName(isGross, getSrcTag))
            }
    }

    // bound is for being passed to super types if it's in the first level
    class Star(param: KSTypeParameter) : Arg<Star>(param) {
        //region conversion
        // later
        override fun convertAlias(): Star = this

        @Deprecated(
            message = "",
            level = DeprecationLevel.WARNING,
            replaceWith = ReplaceWith("convertStar(map)")
        )
        override fun convertStar(): Arg<*> = Bug()

        // later
        override fun convertGeneric(
            map: Map<String, Arg<*>>,
            fromAlias: Boolean
        ):
            Pair<Arg<*>, Boolean> = this to false

        override fun toString(): String = "*"

        fun convertStar(map: Map<String, General<*>>): Arg<*> =
            when {
                param.variance.label == "in"
                // check recycle like Enum<*>
                || param.parentDeclaration!! == param.bounds.firstOrNull()?.resolve()?.declaration
                    -> this

                param.variance.label == ""
                    -> Out(param.getBoundProto(), param).convertAll(map)

                param.variance.label == "out"
                    -> Simple(param.getBoundProto(), param).convertAll(map)

                else -> Bug()
            }
        //endregion

        //region fixed part
        override val allInnerKlasses: List<KSClassDeclaration> = emptyList()
        override fun getContent(getPathImported: (KSClassDeclaration) -> Boolean): String = "*"
        override fun getName(isGross: Boolean, getSrcTag: (KSClassDeclaration) -> String?): String = "*"
        //endregion

        override fun hashCode(): Int {
            return 31 * javaClass.hashCode() + param.hashCode()
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Star) return false
            if (param != other.param) return false
            return true
        }
    }
}