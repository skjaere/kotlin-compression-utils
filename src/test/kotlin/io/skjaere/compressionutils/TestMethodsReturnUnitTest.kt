package io.skjaere.compressionutils

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals

/**
 * JUnit Jupiter silently skips a @Test method that returns a value. In Kotlin that happens to
 * `fun x() = runBlocking { ... }` when the block's last expression has a value (`assertFailsWith`,
 * `assertNotNull`, ...): the test function takes that type. Give such tests `: Unit`.
 */
class TestMethodsReturnUnitTest {

    @Test
    fun `every test method returns Unit so JUnit runs it`() {
        val root = File(javaClass.protectionDomain.codeSource.location.toURI())
        val skipped = root.walkTopDown()
            .filter { it.isFile && it.extension == "class" }
            .map { it.relativeTo(root).path.removeSuffix(".class").replace(File.separatorChar, '.') }
            .map { Class.forName(it, false, javaClass.classLoader) }
            .flatMap { it.declaredMethods.asSequence() }
            .filter { it.isAnnotationPresent(Test::class.java) && it.returnType != Void.TYPE }
            .map { "${it.declaringClass.simpleName}.${it.name} returns ${it.returnType.simpleName}" }
            .sorted()
            .toList()

        assertEquals(emptyList(), skipped, "JUnit never runs these tests")
    }
}
