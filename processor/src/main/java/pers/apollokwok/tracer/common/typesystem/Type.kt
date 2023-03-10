package pers.apollokwok.tracer.common.typesystem

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSTypeAlias
import pers.apollokwok.ksputil.noPackageName
import pers.apollokwok.ksputil.qualifiedName
import pers.apollokwok.ksputil.resolver
import pers.apollokwok.ksputil.simpleName
import pers.apollokwok.ktutil.Bug
import pers.apollokwok.ktutil.lazyFast
import pers.apollokwok.tracer.common.shared.contractedName

internal sealed class Type<T: Type<T>>(val isNullable: Boolean) : Convertible<Type<*>>(){
    companion object{
        // use 'get()=' since anyType.declaration varies every round.
        @Suppress("DANGEROUS_CHARACTERS")
        val `Any？` get() = Specific(
            decl = resolver.builtIns.anyType.declaration as KSClassDeclaration,
            args = emptyList(),
            isNullable = true,
            hasAlias = false,
            hasConvertibleStar = false,
        )
    }

    final override fun toString(): String =
        buildString {
            when(this@Type){
                is Generic -> append(name)

                is Alias -> {
                    append(this@Type.decl)

                    if (args.any()){
                        append("<")
                        append(args.joinToString(", "))
                        append(">")
                    }
                }

                is Specific -> {
                    append(this@Type.decl.noPackageName())

                    if (args.any()){
                        append("<")
                        append(args.joinToString(", "))
                        append(">")
                    }
                }

                is Compound -> {
                    append("[")
                    append(types.joinToString(", "))
                    append("]")
                }
            }

            if (isNullable) append("?")
        }

    class Generic(
        val name: String,
        val bound: Type<*>,
        isNullable: Boolean,
    ) :
        Type<Generic>(isNullable)
    {
        //region conversion
        override fun convertAlias(): Generic = this

        override fun convertStar(): Generic = this

        override fun convertGeneric(
            map: Map<String, Arg<*>>,
            fromAlias: Boolean,
        ): Pair<Type<*>, Boolean> {
            val arg = map[name]

            val requireOut = !fromAlias && arg !is Arg.Simple

            val type = when(arg){
                null -> {
                    require(!fromAlias)
                    bound.convertGeneric(map, fromAlias).first
                }

                is Arg.Star -> error("This should be handled by parent arg.")

                is Arg.General<*> -> arg.type
            }

            val isNullable = this.isNullable || type.isNullable

            return type.updateNullability(isNullable) to requireOut
        }
        //endregion

        //region fun copy
        fun copy(
            name: String = this.name,
            bound: Type<*> = this.bound,
            isNullable: Boolean = this.isNullable,
        ) =
            if (name == this.name
                && bound == this.bound
                && isNullable == this.isNullable
            )
                this
            else
                Generic(name, bound, isNullable)
        //endregion

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as Generic

            if (name != other.name) return false
            if (bound != other.bound) return false
            if (isNullable != other.isNullable) return false

            return true
        }

        override fun hashCode(): Int {
            var result = name.hashCode()
            result = 31 * result + bound.hashCode()
            result = 31 * result + isNullable.hashCode()
            return result
        }

        //region fixed part
        override val allInnerKlasses: List<KSClassDeclaration> get() = Bug()

        override fun getContent(getPathImported: (KSClassDeclaration) -> Boolean): String = Bug()

        override fun getName(isGross: Boolean, getSrcTag: (KSClassDeclaration) -> String?): String = Bug()
        //endregion
    }

    class Alias(
        val decl: KSTypeAlias,
        val args: List<Arg<*>>,
        isNullable: Boolean,
        val genericName: String? = null,
    ) :
        Type<Alias>(isNullable)
    {
        //region conversion
        override fun convertAlias(): Specific {
            val newMap = args.map { it.convertAlias() }.associateBy { it.param.simpleName() }

            val converted = decl
                .type
                .toProto()
                .convertGeneric(newMap, true)
                .first
                .convertAlias() as Specific

            val isNullable = this.isNullable || converted.isNullable

            return converted.copy(hasAlias = false, isNullable = isNullable)
        }

        // may be unreachable
        override fun convertStar(): Alias = error("There should be no alias when converting star.")

        override fun convertGeneric(
            map: Map<String, Arg<*>>,
            fromAlias: Boolean,
        ): Pair<Alias, Boolean> {
            val replaced = args.map { it.convertGeneric(map, fromAlias) }
            val requireOut = replaced.any { it.second }
            return copy(args = replaced.map { it.first }) to requireOut
        }
        //endregion

        //region fun copy
        fun copy(
            decl: KSTypeAlias = this.decl,
            args: List<Arg<*>> = this.args,
            isNullable: Boolean = this.isNullable,
            genericName: String? = this.genericName,
        ) =
            if (decl == this.decl
                && args == this.args
                && isNullable == this.isNullable
                && genericName == this.genericName
            )
                this
            else
                Alias(decl, args, isNullable, genericName)
        //endregion

        override fun hashCode(): Int {
            var result = decl.hashCode()
            result = 31 * result + args.hashCode()
            result = 31 * result + (genericName?.hashCode() ?: 0)
            result = 31 * result + isNullable.hashCode()
            return result
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as Alias

            if (decl != other.decl) return false
            if (args != other.args) return false
            if (genericName != other.genericName) return false
            if (isNullable != other.isNullable) return false

            return true
        }

        //region fixed part
        override val allInnerKlasses: List<KSClassDeclaration> get() = Bug()

        override fun getContent(getPathImported: (KSClassDeclaration) -> Boolean): String = Bug()

        override fun getName(isGross: Boolean, getSrcTag: (KSClassDeclaration) -> String?): String = Bug()
        //endregion
    }

    class Specific(
        val decl: KSClassDeclaration,
        val args: List<Arg<*>>,
        isNullable: Boolean,
        val genericName: String? = null,
        val hasAlias: Boolean,
        val hasConvertibleStar: Boolean,
    ) :
        Type<Specific>(isNullable)
    {
        //region conversion
        override fun convertAlias(): Specific =
            if (hasAlias)
                copy(args = args.map { it.convertAlias() })
            else
                this

        override fun convertStar(): Specific {
            if (!hasConvertibleStar) return this

            val fromGeneral = args
                .filterIsInstance<Arg.General<*>>()
                .map { it.convertStar() }

            val map = fromGeneral.associateBy { it.param.simpleName() }

            val fromStar = args
                .filterIsInstance<Arg.Star>()
                .map { it.convertStar(map) }

            val convertedArgs = fromGeneral + fromStar

            val newArgs= args.map { arg ->
                convertedArgs.first { it.param == arg.param }
            }

            return copy(args = newArgs, hasConvertibleStar = false)
        }

        override fun convertGeneric(
            map: Map<String, Arg<*>>,
            fromAlias: Boolean
        ): Pair<Specific, Boolean> {
            val replaced = args.map { it.convertGeneric(map, fromAlias) }
            val requireOut = replaced.any { it.second }
            return copy(args = replaced.map { it.first }) to requireOut
        }
        //endregion

        //region fun copy
        fun copy(
            decl: KSClassDeclaration = this.decl,
            args: List<Arg<*>> = this.args,
            isNullable: Boolean = this.isNullable,
            genericName: String? = this.genericName,
            hasAlias: Boolean = this.hasAlias,
            hasConvertibleStar: Boolean = this.hasConvertibleStar,
        ): Specific =
            if (decl == this.decl
                && args == this.args
                && isNullable == this.isNullable
                && genericName == this.genericName
                && hasAlias == this.hasAlias
                && hasConvertibleStar == this.hasConvertibleStar
            )
                this
            else
                Specific(decl, args, isNullable, genericName, hasAlias, hasConvertibleStar)
        //endregion

        //region fixed part
        override val allInnerKlasses: List<KSClassDeclaration> by lazyFast {
            args.flatMap { it.allInnerKlasses } + decl
        }

        override fun getContent(getPathImported: (KSClassDeclaration) -> Boolean): String? =
            buildString{
                if (getPathImported(decl))
                    append(decl.noPackageName())
                else
                    append(decl.qualifiedName()!!)

                if (args.any()){
                    append("<")

                    args.map{ it.getContent(getPathImported) ?: return null }
                        .joinToString(", ")
                        .let(::append)

                    append(">")
                }

                if (isNullable) append("?")
            }

        // gross and the other
        override fun getName(isGross: Boolean, getSrcTag: (KSClassDeclaration) -> String?): String =
            buildString {
                @Suppress("LocalVariableName")
                val `need？` = isNullable && !isGross

                genericName?.let { append("${genericName}_") }

                val (isGeneralFunction, isSuspendFunction) =
                    arrayOf(
                        Function::class.qualifiedName!!,
                        "kotlin.coroutines.SuspendFunction",
                    )
                    .map {
                        decl.qualifiedName()!!.startsWith(it)
                        && decl.qualifiedName()!!.substringAfter(it).all(Char::isDigit)
                    }

                if (isGeneralFunction || isSuspendFunction) {
                    val body = buildString {
                        if (isSuspendFunction && !isGross) append("⍒")
                        append("❨")

                        args.dropLast(1)
                            .joinToString("，") { it.getName(isGross, getSrcTag) }
                            .let(::append)

                        append("❩-›")
                        append(args.last().getName(isGross, getSrcTag))
                    }

                    if (`need？`)
                        append("❨$body❩？")
                    else
                        append(body)
                } else {
                    append(decl.contractedName)

                    if (`need？` && args.none()) append("？")

                    getSrcTag(decl)?.let { append("_$it") }

                    if (args.any()) {
                        args.joinToString(
                            prefix = "‹",
                            transform = { it.getName(isGross, getSrcTag) },
                            separator = "，",
                            postfix = "›",
                        )
                        .let(::append)

                        if (`need？`) append("？")
                    }
                }
            }
        //endregion

        override fun hashCode(): Int {
            var result = decl.hashCode()
            result = 31 * result + args.hashCode()
            result = 31 * result + (genericName?.hashCode() ?: 0)
            result = 31 * result + hasAlias.hashCode()
            result = 31 * result + hasConvertibleStar.hashCode()
            result = 31 * result + isNullable.hashCode()
            return result
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as Specific

            if (decl != other.decl) return false
            if (args != other.args) return false
            if (genericName != other.genericName) return false
            if (hasAlias != other.hasAlias) return false
            if (hasConvertibleStar != other.hasConvertibleStar) return false
            if (isNullable != other.isNullable) return false

            return true
        }
    }

    class Compound(
        val types: List<Type<*>>,
        isNullable: Boolean,
        val genericName: String? = null,
        val isDeclarable: Boolean = true,
    ):
        Type<Compound>(isNullable)
    {
        init {
            require(types.count() >= 2)
        }

        //region conversion
        override fun convertAlias(): Compound = copy(types = types.map { it.convertAlias() })

        override fun convertStar(): Compound = copy(types = types.map { it.convertStar() })

        override fun convertGeneric(
            map: Map<String, Arg<*>>,
            fromAlias: Boolean
        ):
            Pair<Compound, Boolean> = this to false
        //endregion

        //region fun copy
        fun copy(
            types: List<Type<*>> = this.types,
            isNullable: Boolean = this.isNullable,
            genericName: String? = this.genericName,
            isReturnable: Boolean = this.isDeclarable,
        ): Compound =
            if (types == this.types
                && isNullable == this.isNullable
                && genericName == this.genericName
                && isReturnable == this.isDeclarable
            )
                this
            else
                Compound(types, isNullable, genericName, isReturnable)
        //endregion

        //region fixed part
        override val allInnerKlasses: List<KSClassDeclaration> by lazyFast {
            types.flatMap { it.allInnerKlasses }
        }

        override fun getContent(getPathImported: (KSClassDeclaration) -> Boolean): String? =
            if (isDeclarable) "*" else null

        override fun getName(isGross: Boolean, getSrcTag: (KSClassDeclaration) -> String?): String =
            buildString {
                genericName?.let { append("${it}_") }
                append("‹")
                append(types.joinToString("，"){ it.getName(isGross, getSrcTag) })
                append("›")
                if (isNullable && !isGross) append("？")
            }
        //endregion

        override fun hashCode(): Int {
            var result = types.hashCode()
            result = 31 * result + (genericName?.hashCode() ?: 0)
            result = 31 * result + isNullable.hashCode()
            result = 31 * result + isDeclarable.hashCode()
            return result
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as Compound

            if (types != other.types) return false
            if (genericName != other.genericName) return false
            if (isNullable != other.isNullable) return false
            if (isDeclarable != other.isDeclarable) return false

            return true
        }
    }
}