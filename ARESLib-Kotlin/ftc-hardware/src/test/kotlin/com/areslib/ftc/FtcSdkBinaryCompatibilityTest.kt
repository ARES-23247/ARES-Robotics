package com.areslib.ftc

import java.io.File
import java.util.jar.JarFile
import org.firstinspires.ftc.vision.apriltag.AprilTagDetection
import org.junit.jupiter.api.Test
import org.objectweb.asm.*
import kotlin.test.assertTrue

/** Checks the actual compiled robot calls, not merely matching mock source names. */
class FtcSdkBinaryCompatibilityTest {
    private data class Shape(
        val parents: List<String>, val isInterface: Boolean,
        val methods: MutableMap<String, Int> = linkedMapOf(),
        val fields: MutableMap<String, Int> = linkedMapOf(),
    )

    @Test fun `desktop mocks link every SDK member referenced by production hardware bytecode`() {
        val sdk = linkedMapOf<String, Shape>()
        for (path in System.getProperty("ares.ftc.sdk.classpath").split(File.pathSeparator))
            loadJar(File(path), sdk)
        val mocks = linkedMapOf<String, Shape>()
        loadJar(File(AprilTagDetection::class.java.protectionDomain.codeSource.location.toURI()), mocks)
        val production = linkedMapOf<String, Shape>()
        val classes = File(System.getProperty("ares.ftc.hardware.classes")).walkTopDown().filter { it.extension == "class" }.toList()
        for (file in classes) loadClass(ClassReader(file.readBytes()), production)
        val failures = sortedSetOf<String>()
        var checked = 0
        fun sdkOwner(owner: String, key: String, field: Boolean, visited: MutableSet<String> = hashSetOf()): String? {
            if (!visited.add(owner)) return null
            val shape = sdk[owner] ?: production[owner] ?: return null
            if ((if (field) shape.fields else shape.methods).containsKey(key)) return owner.takeIf { it in sdk }
            if (key.startsWith("<init>")) return null
            for (parent in shape.parents) sdkOwner(parent,key,field,visited)?.let { return it }
            return null
        }
        fun member(owner: String, key: String, field: Boolean, visited: MutableSet<String> = hashSetOf()): Int? {
            if (!visited.add(owner)) return null
            if (owner.startsWith("java/")) {
                val type = Class.forName(owner.replace('/', '.'))
                if (!field) return type.methods.firstOrNull { it.name + Type.getMethodDescriptor(it) == key }?.modifiers
                return type.fields.firstOrNull { it.name + Type.getDescriptor(it.type) == key }?.modifiers
            }
            val shape = mocks[owner] ?: production[owner] ?: return null
            (if (field) shape.fields else shape.methods)[key]?.let { return it }
            if (key.startsWith("<init>")) return null
            for (parent in shape.parents) member(parent, key, field, visited)?.let { return it }
            return null
        }
        for (file in classes) {
            ClassReader(file.readBytes()).accept(object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?): MethodVisitor =
                    object : MethodVisitor(Opcodes.ASM9) {
                        private fun check(owner: String, key: String, field: Boolean, static: Boolean, isInterface: Boolean? = null) {
                            val declaredOwner = sdkOwner(owner,key,field) ?: return
                            // JVM lookup begins at the referenced owner, so a mock may legally
                            // implement an inherited SDK member on that owner itself.
                            val actual = sdk[owner] ?: production.getValue(owner)
                            checked++
                            val mock = mocks[owner] ?: production[owner]
                            val prefix = "${file.name}: $owner.$key"
                            if (mock == null) { failures.add("$prefix missing mock class"); return }
                            if (isInterface != null && actual.isInterface != mock.isInterface)
                                failures.add("$prefix interface/class mismatch")
                            val accessFlags = member(owner,key,field)
                            if (accessFlags == null) failures.add("$prefix missing ${if (field) "field" else "method"}")
                            else if ((accessFlags and Opcodes.ACC_STATIC != 0) != static)
                                failures.add("$prefix static/instance mismatch")
                        }
                        override fun visitFieldInsn(opcode: Int, owner: String, name: String, descriptor: String) =
                            check(owner, name+descriptor, true, opcode == Opcodes.GETSTATIC || opcode == Opcodes.PUTSTATIC)
                        override fun visitMethodInsn(opcode: Int, owner: String, name: String, descriptor: String, isInterface: Boolean) =
                            check(owner, name+descriptor, false, opcode == Opcodes.INVOKESTATIC, isInterface)
                    }
            }, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
        }
        assertTrue(checked > 100, "No representative SDK calls were inspected: $checked")
        assertTrue(failures.isEmpty(), "SDK/mock binary mismatch (${failures.size}):\n"+failures.joinToString("\n"))
        println("SDK/mock ABI verified: $checked member instructions")
    }

    private fun loadJar(file: File, into: MutableMap<String, Shape>) = JarFile(file).use { jar ->
        for (entry in jar.entries()) if (entry.name.endsWith(".class")) {
            loadClass(ClassReader(jar.getInputStream(entry).use { it.readBytes() }), into)
        }
    }

    private fun loadClass(reader: ClassReader, into: MutableMap<String, Shape>) {
        reader.accept(object : ClassVisitor(Opcodes.ASM9) {
                private lateinit var shape: Shape
                override fun visit(version: Int, access: Int, name: String, signature: String?, superName: String?, interfaces: Array<out String>?) {
                    shape = Shape(listOfNotNull(superName) + (interfaces?.toList() ?: emptyList()), access and Opcodes.ACC_INTERFACE != 0)
                    into[name] = shape
                }
                override fun visitField(access: Int, name: String, descriptor: String, signature: String?, value: Any?): FieldVisitor? {
                    shape.fields[name+descriptor] = access; return null
                }
                override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?): MethodVisitor? {
                    shape.methods[name+descriptor] = access; return null
                }
            }, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
    }
}
