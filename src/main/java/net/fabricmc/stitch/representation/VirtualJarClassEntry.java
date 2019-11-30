package net.fabricmc.stitch.representation;

import java.io.IOException;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Form of {@link JarClassEntry} which comes from the classpath rather than an explicit jar
 */
public final class VirtualJarClassEntry extends JarClassEntry {
	private static String splitName(String name) {
		int split = name.lastIndexOf('$');
		if (split > 0) return name.substring(split + 1);

		split = name.lastIndexOf('/');
		return split > 0 ? name.substring(split + 1) : name;
	}

	public VirtualJarClassEntry(Class<?> clazz) throws IOException {
		this(clazz.getName().replace('.', '/'));
	}

	public VirtualJarClassEntry(String fullyQualifiedName) throws IOException {
		super(splitName(fullyQualifiedName), fullyQualifiedName);

		new ClassReader(fullyQualifiedName).accept(new ClassVisitor(Opcodes.ASM7) {
			@Override
			public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
				populate(access, signature, superName, interfaces);

				super.visit(version, access, name, signature, superName, interfaces);
			}

			@Override
			public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
				JarFieldEntry field = new JarFieldEntry(access, name, descriptor, signature);
				fields.put(field.getKey(), field);

				return super.visitField(access, name, descriptor, signature, value);
			}

			@Override
			public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
				JarMethodEntry method = new JarMethodEntry(access, name, descriptor, signature);
				methods.put(method.getKey(), method);

				return super.visitMethod(access, name, descriptor, signature, exceptions);
			}
		}, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
	}
}