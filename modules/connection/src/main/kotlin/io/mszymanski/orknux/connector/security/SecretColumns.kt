package io.mszymanski.orknux.connector.security

import jakarta.persistence.Column
import jakarta.persistence.Convert
import jakarta.persistence.EntityManagerFactory
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.stereotype.Component
import java.lang.reflect.Field

/**
 * Every column in this database that holds a credential.
 *
 * Read from the entities rather than written down beside them. There used to be
 * a hand-kept list in [SecretMigration] and it named four of the eight fields
 * that carry `@Convert(SecretConverter)` — the four that existed when it was
 * written. The four added since were encrypted on the way in by the converter
 * and never swept on upgrade, so `shell.private_key` sat in the database as the
 * SSH key it is on every installation that predates it. A list that has to be
 * remembered is a list that will be wrong, and the annotation is the only place
 * that cannot be forgotten: a field without it is not encrypted at all, so
 * there is no such thing as a secret column this does not see.
 *
 * The names come from the same two annotations Hibernate reads, and where an
 * entity does not name its table or a field its column, the same rule Spring
 * Boot's default physical naming strategy applies is applied here. That is a
 * second implementation of one rule, which is a thing that can drift — so
 * `SecretColumnsTest` asks the database whether every column named here exists,
 * on the schema the application actually runs against.
 *
 * Derived once, lazily. The metamodel is fixed for the life of the context and
 * the reflection is not free, and the first caller is either the boot sweep or
 * somebody opening the doctor.
 */
@Component
class SecretColumns(private val entityManagers: EntityManagerFactory) {

    /** One credential column, and the key column its table is addressed by. */
    data class SecretColumn(val table: String, val column: String, val id: String = "id") {

        /** How the doctor names it to a person: the same `table.column` a query would use. */
        override fun toString(): String = "$table.$column"
    }

    /** Sorted, so a log line and a diagnostic card list them in the same order twice running. */
    val all: List<SecretColumn> by lazy {
        entityManagers.metamodel.entities
            .map { it.javaType }
            .flatMap { entity -> secretFields(entity).map { SecretColumn(tableOf(entity), columnOf(it), idOf(entity)) } }
            .distinct()
            .sortedWith(compareBy({ it.table }, { it.column }))
    }

    /**
     * The fields of one entity that go through the cipher, inherited ones included.
     *
     * Kotlin puts an annotation from a constructor property onto the backing
     * field when the annotation cannot target a parameter, which is what
     * `jakarta.persistence.Convert` is — and it is why Hibernate finds these at
     * all, since it reads the same fields.
     */
    private fun secretFields(entity: Class<*>): List<Field> = generateSequence(entity) { it.superclass }
        .takeWhile { it != Any::class.java }
        .flatMap { it.declaredFields.asSequence() }
        .filter { it.getAnnotation(Convert::class.java)?.converter == SecretConverter::class }
        .toList()

    /**
     * The key a row is written back by. Not always `id`: `workspace_search` is
     * keyed by its workspace, and assuming `id` there failed the read, so a key
     * stored in the clear was never sealed and only a WARN said so.
     */
    private fun idOf(entity: Class<*>): String = generateSequence(entity) { it.superclass }
        .takeWhile { it != Any::class.java }
        .flatMap { it.declaredFields.asSequence() }
        .firstOrNull { it.isAnnotationPresent(Id::class.java) }
        ?.let { columnOf(it) }
        ?: "id"

    private fun tableOf(entity: Class<*>): String =
        entity.getAnnotation(Table::class.java)?.name?.takeIf { it.isNotBlank() } ?: underscored(entity.simpleName)

    private fun columnOf(field: Field): String =
        field.getAnnotation(Column::class.java)?.name?.takeIf { it.isNotBlank() } ?: underscored(field.name)

    /**
     * `keyPassphrase` to `key_passphrase`, the way Spring Boot's
     * `CamelCaseToUnderscoresNamingStrategy` does it — a separator before every
     * capital that has not just had one, and everything lowered.
     */
    private fun underscored(name: String): String = buildString {
        for (character in name) {
            if (character.isUpperCase() && isNotEmpty() && last() != '_') append('_')
            append(character.lowercaseChar())
        }
    }
}
