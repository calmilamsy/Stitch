package net.fabricmc.stitch.commands;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.Function;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import net.fabricmc.mappings.EntryTriple;
import net.fabricmc.stitch.Command;
import net.fabricmc.stitch.commands.CommandMergeTiny.TinyFile;
import net.fabricmc.stitch.commands.CommandMergeTiny.TinyFile.MethodLine;
import net.fabricmc.stitch.commands.CommandMergeTiny.TinyLine;
import net.fabricmc.stitch.representation.Access;
import net.fabricmc.stitch.representation.ClassStorage;
import net.fabricmc.stitch.representation.JarClassEntry;
import net.fabricmc.stitch.representation.JarMethodEntry;
import net.fabricmc.stitch.representation.JarReader.Builder;
import net.fabricmc.stitch.representation.JarRootEntry;
import net.fabricmc.stitch.representation.VirtualJarClassEntry;
import net.fabricmc.stitch.util.StitchUtil;
import net.fabricmc.stitch.util.StitchUtil.FileSystemDelegate;

public class CommandFixBridges extends Command {
	public CommandFixBridges() {
		super("fixBridges");
	}

	@Override
	public String getHelpString() {
		return "<jar> <mappings-in> <mappings-out> <jar-namespace> [corrective-namespaces...]";
	}

	@Override
	public boolean isArgumentCountValid(int count) {
		return count >= 5;
	}

	@Override
	public void run(String[] args) throws Exception {
		run(new File(args[0]), Paths.get(args[1]), Paths.get(args[2]), args[3], Arrays.copyOfRange(args, 4, args.length));
	}

	public static void run(File jar, Path mappingsIn, Path mappingsOut, String jarNamespace, String... correctiveNamespaces) throws IOException {
		JarRootEntry jarEntry = new JarRootEntry(jar);
		Builder.create(jarEntry).joinMethodEntries(false).build().apply();

		System.out.println("Looking for bridges...");

		Map<EntryTriple, EntryTriple> bridges;
		try (FileSystemDelegate fs = StitchUtil.getJarFileSystem(jar, false)) {
			bridges = jarEntry.getAllClasses().parallelStream().filter(classEntry -> classEntry.getFullyQualifiedName().startsWith("net/minecraft/")).flatMap(classEntry -> {
				Set<Method> potentialBridges = StitchUtil.newIdentityHashSet();
				Set<Method> potentiallyBridged = StitchUtil.newIdentityHashSet();

				for (JarMethodEntry methodEntry : classEntry.getMethods()) {
					Method method = new Method(classEntry, methodEntry);

					//All bridges must have a parent (and won't be made final by the compiler)
					//Methods being bridged to shouldn't ever have parents (as they're narrowing a parent's method), but might from an unrelated interface
					(!method.isFinal() && method.hasParent(jarEntry) ? potentialBridges : potentiallyBridged).add(method);
				}
				assert potentiallyBridged.stream().allMatch(method -> !Access.isBridge(method.method.getAccess())):
					potentiallyBridged.stream().filter(method -> Access.isBridge(method.method.getAccess())).map(method -> StitchUtil.memberString(method.asEntry()))
					.collect(Collectors.joining(", ", "Didn't suspect bridge flagged method(s) are bridges: [", "]?")); //Hopefully things aren't misflagged as bridges

				//No method looks like it is probably a bridge
				if (potentialBridges.isEmpty()) return Stream.empty();

				BridgeDetector detector = new BridgeDetector(jarEntry, potentialBridges.toArray(new Method[0])); {
					Path path = fs.get().getPath(classEntry.getKey() + ".class");
					assert path != null: "Failed to make class file path for " + classEntry;
					try (InputStream in = Files.newInputStream(path)) {
						new ClassReader(in).accept(detector, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
					} catch (IOException e) {
						throw new UncheckedIOException("Unable to read class file for " + classEntry + " from " + path, e);
					}
				}

				//Didn't find anything which might be a bridge within the class
				if (!detector.foundAnyBridges()) return Stream.empty();

				//Should find all the bridged methods in the potentiallyBridged list
				assert detector.foundBridged().allMatch(bridged -> bridged != null && potentiallyBridged.stream().map(Method::asEntry).anyMatch(bridged::equals)):
					//Technically should filter with null too, but null clearly will never be in the class. Asserts in BridgeDetector should protect from it instead
					detector.foundBridged().filter(bridged -> bridged != null && potentiallyBridged.stream().map(Method::asEntry).noneMatch(bridged::equals))
					.map(StitchUtil::memberString).collect(Collectors.joining(", ", "Found bridges which didn't appear in class: [", "]?"));

				assert detector.foundBridges().allMatch(bridge -> potentialBridges.stream().map(Method::asEntry).anyMatch(bridge::equals));
				assert potentialBridges.stream().filter(method -> detector.foundBridges().noneMatch(method.asEntry()::equals))
					.noneMatch(method -> Access.isBridge(method.method.getAccess())); //Shouldn't fail to match bridges which are flagged as bridges

				return detector.bridgeMap().entrySet().stream();
			}).collect(Collectors.toConcurrentMap(Entry::getKey, Entry::getValue));

			if (!bridges.isEmpty()) {
				Set<EntryTriple> bridgeMethods = new HashSet<>(bridges.keySet());
				System.out.println("Verifying " + bridgeMethods.size() + " found bridges");

				jarEntry.getAllClasses().parallelStream().forEach(classEntry -> {
					Path path = fs.get().getPath(classEntry.getKey() + ".class");
					assert path != null: "Failed to make class file path for " + classEntry;

					assert Files.exists(path): "Broken class name: " + classEntry.getKey(); //Couldn't find class, most likely from an inner class not remapping its outer properly
					try (InputStream in = Files.newInputStream(path)) {
						new ClassReader(in).accept(new ClassVisitor(Opcodes.ASM7) {
							@Override
							public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
								return new MethodVisitor(Opcodes.ASM7) {
									@Override
									public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
										if (bridgeMethods.contains(new EntryTriple(owner, name, descriptor))) {//That's not good
											System.err.println("Direct reference to suspected bridge method: " + owner + '/' + name + descriptor);
										}
									}
								};
							}
						}, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
					} catch (IOException e) {
						throw new UncheckedIOException("Unable to read class file for " + classEntry + " from " + path, e);
					}
				});

				System.out.println("Verification complete, writing to mapping file");
			} else {
				System.out.println("Unable to find any bridges in input jar");

				Files.copy(mappingsIn, mappingsOut, StandardCopyOption.COPY_ATTRIBUTES);
				return; //Nothing more to do
			}
		}

		TinyFile input = new TinyFile(mappingsIn);
		Map<EntryTriple, EntryTriple> bridgedMethods = bridges.entrySet().stream().collect(Collectors.toMap(Entry::getValue, Entry::getKey, (left, right) -> {
			//System.out.println("Double bridge parents: " + StitchUtil.memberString(left) + " and " + StitchUtil.memberString(right));
			assert Objects.equals(left.getOwner(), right.getOwner());

			assert !Objects.equals(left.getDesc(), right.getDesc());
			Type leftReturn = Type.getReturnType(left.getDesc());
			Type rightReturn = Type.getReturnType(right.getDesc());

			if (!leftReturn.equals(rightReturn)) {
				return isLeftYounger(jarEntry, leftReturn, rightReturn) ? right : left;
			}

			Type[] leftArgs = Type.getArgumentTypes(left.getDesc());
			Type[] rightArgs = Type.getArgumentTypes(right.getDesc());
			assert leftArgs.length == rightArgs.length;

			for (int i = 0; i < leftArgs.length; i++) {
				Type leftArg = leftArgs[i];
				Type rightArg = rightArgs[i];

				if (!leftArg.equals(rightArg)) {
					return isLeftYounger(jarEntry, leftArg, rightArg) ? right : left;
				}
			}

			throw new IllegalStateException("Could not deduce whether to pick " + StitchUtil.memberString(left) + " or " + StitchUtil.memberString(right));
		}));

		List<String> namespaces = input.getSortedNamespaces().collect(Collectors.toList());
		Set<String> targetNamespaces = new HashSet<>();
		Collections.addAll(targetNamespaces, correctiveNamespaces);

		try (BufferedWriter writer = Files.newBufferedWriter(mappingsOut, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
			writer.write(input.firstLine);
			writer.newLine();

			for (TinyLine line : input.lines()) {
				if (line.getClass() == MethodLine.class) {
					MethodLine methodLine;
					EntryTriple jarMethod = (methodLine = (MethodLine) line).get(jarNamespace);

					assert !bridges.containsKey(jarMethod);
					if (bridgedMethods.containsKey(jarMethod)) {
						EntryTriple targetMethod = bridgedMethods.get(jarMethod);

						Function<String, EntryTriple> targetLine = null;
						for (TinyLine l : input.lines()) {
							if (l.getClass() == MethodLine.class) {
								EntryTriple triple = ((MethodLine) l).get(jarNamespace);

								if (triple != null && triple.getName().equals(targetMethod.getName()) && triple.getDesc().equals(targetMethod.getDesc())) {
									targetLine = ((MethodLine) l)::get;
									break;
								}
							}
						}
						if (targetLine == null) {
							System.out.println("Unable to find " + StitchUtil.memberString(targetMethod) + " in mappings for " + StitchUtil.memberString(jarMethod));
							//throw new IllegalStateException("Unable to find " + StitchUtil.memberString(targetMethod));
							targetLine = namespace -> targetMethod; //Improvise and use the target method's name for everything
						}

						EntryTriple primary = methodLine.get(namespaces.get(0));

						writer.write("METHOD\t");
						writer.write(primary.getOwner());
						writer.write('\t');
						writer.write(primary.getDesc());

						for (String namespace : namespaces) {
							writer.write('\t');
							EntryTriple name = targetNamespaces.contains(namespace) ? targetLine.apply(namespace) : methodLine.get(namespace);
							if (name != null) writer.write(name.getName());
						}

						writer.newLine();
						continue;
					}
				}

				writer.write(line.line);
				writer.newLine();
			}
		}
	}

	private static boolean isLeftYounger(ClassStorage classes, Type type, Type potentialParent) {
		assert !type.equals(potentialParent);
		assert type.getSort() == Type.OBJECT;
		String parentName = potentialParent.getInternalName();

		if ("java/lang/Object".equals(parentName)) {
			assert !"java/lang/Object".equals(type.getInternalName());
			return true;
		}

		JarClassEntry origin = classes.getClass(type.getInternalName(), false);
		if (origin == null) {
			assert classes.getClass(parentName, false) == null;
			throw new IllegalStateException("Not sure which is younger between " + type + " and " + parentName);
		}

		JarClassEntry target = classes.getClass(parentName, false);
		if (target == null) return true; //Ehhhh probably

		if (!target.isInterface()) {
			JarClassEntry parent = origin;
	    	do {
	    		parent = parent.getSuperClass(classes);
	    	} while (parent != null && parent != target);

	    	//Found the target as a parent class <=> parent not null
	    	return parent != null;
		} else {
	    	Deque<JarClassEntry> interfaces = new ArrayDeque<>(origin.getAllInterfaces(classes));

			JarClassEntry itf;
			while ((itf = interfaces.poll()) != null) {
				if (itf == target) {
					return true; //Found the right interface
				} else {
					interfaces.addAll(itf.getAllInterfaces(classes));
				}
			}

			return false; //Couldn't find any matching interfaces in the hierarchy
		}
	}

	private static final class Method {
		private static final Map<String, JarClassEntry> VIRTUAL_CLASS_STORAGE = new HashMap<>();
		static {
			VIRTUAL_CLASS_STORAGE.put("java/lang/Object", null); //Don't try Object, things break
		}
		public final JarClassEntry owner;
		public final JarMethodEntry method;

		public Method(JarClassEntry owner, JarMethodEntry method) {
			this.owner = owner;
			this.method = method;
		}

		public EntryTriple asEntry() {
			return new EntryTriple(owner.getFullyQualifiedName(), method.getName(), method.getDescriptor());
		}

		public boolean isFinal() {
			return Modifier.isFinal(method.getAccess());
		}

		public boolean hasParent(ClassStorage classes) {
			return !Access.isPrivateOrStatic(method.getAccess()) && hasParent0((name, create) -> {
				JarClassEntry out = classes.getClass(name, create);
				if (out != null || create) return out;

				//Tack on additional library classes (ie Java ones) for the sake of inheritance
				if (!VIRTUAL_CLASS_STORAGE.containsKey(name)) {
					try {
						assert name != null: "Tried to get null class name?";
						VIRTUAL_CLASS_STORAGE.put(name, new VirtualJarClassEntry(name));
					} catch (IOException e) {
						System.err.println("Unable to create virtual class for " + name);
						e.printStackTrace();
						VIRTUAL_CLASS_STORAGE.put(name, null);
					}
				}

				return VIRTUAL_CLASS_STORAGE.get(name);
			});
		}

		private boolean hasParent0(ClassStorage classes) {
			JarClassEntry parent = owner;
	    	do {
	    		try {
	    			parent = parent.getSuperClass(classes);
	    		} catch (AssertionError e) {
	    			System.err.println("Crash finding the super of " + parent + " (in the hierachy of " + owner + ')');
	    			throw e;
	    		}
	    	} while (parent != null && !parent.getMethods().contains(method));

	    	if (parent != null && parent.getMethods().contains(method)) {
	    		return true;
	    	}

	    	Deque<JarClassEntry> interfaces = new ArrayDeque<>(owner.getAllInterfaces(classes));

			JarClassEntry itf;
			while ((itf = interfaces.poll()) != null) {
				if (itf.getMethods().contains(method)) {
					return true;
				} else {
					interfaces.addAll(itf.getAllInterfaces(classes));
				}
			}

			return false; //Seems not
		}
	}

	private static class BridgeDetector extends ClassVisitor {
		private class BridgeVisitor extends MethodVisitor {
			private final MethodDetail targetBridge;
			private final Type[] args;
			private final Type returnType;
			private final int argSize;
			private boolean seenReturn;
			private boolean[] seenArg;
			private EntryTriple seenMethod;
			private boolean valid;

			public BridgeVisitor(MethodDetail targetBridge) {
				super(Opcodes.ASM7);

				this.targetBridge = targetBridge;
				String descriptor = targetBridge.desc;
				args = Type.getArgumentTypes(descriptor);

				int localSize = Arrays.stream(args).mapToInt(Type::getSize).sum() + 1;
				argSize = Math.max(localSize, (returnType = Type.getReturnType(descriptor)).getSize());

				seenArg = new boolean[localSize];
				for (int arg = 0, i = 1; arg < args.length; arg++) {
					int argSize = args[arg].getSize();

					if (argSize > 1) {
						//See the extra part of longs and doubles as they'll never get (directly) referenced otherwise
						Arrays.fill(seenArg, i + 1, i += argSize, true);
					} else {
						assert argSize == 1; //Shouldn't get void parameters
						i++;
					}
				}
			}

			private void invalidate() {
				assert valid;
				valid = false;
				assert !Access.isBridge(targetBridge.access): "Flagged bridge " + StitchUtil.memberString(targetBridge.asEntry()) + " is not a bridge?";
			}

			private void ensureExpected(int opcode, int... validOpcodes) {
				if (!valid) return;

				for (int validOpcode : validOpcodes) {
					if (validOpcode == opcode) return;
				}

				invalidate();
			}

			@Override
			public void visitCode() {
				valid = true; //We're not abstract
			}

			@Override
			public void visitInsn(int opcode) {
				//Could match return opcode to the return type, but so long as there's only one it should be right
				ensureExpected(opcode, Opcodes.IRETURN, Opcodes.LRETURN, Opcodes.FRETURN, Opcodes.DRETURN, Opcodes.ARETURN, Opcodes.RETURN);

				if (valid) {
					if (seenReturn) invalidate(); else seenReturn = true;
				}
			}

			@Override
			public void visitIntInsn(int opcode, int operand) {
				if (valid) invalidate(); //Shouldn't see any of these
			}

			@Override
			public void visitVarInsn(int opcode, int var) {
				//Could match opcode to the argument type, but so long as it's only loaded once it should be right
				ensureExpected(opcode, Opcodes.ILOAD, Opcodes.LLOAD, Opcodes.FLOAD, Opcodes.DLOAD, Opcodes.ALOAD);

				if (valid) {
					assert var < seenArg.length;
					if (seenArg[var]) invalidate(); else seenArg[var] = true; 
				}
			}

			@Override
			public void visitTypeInsn(int opcode, String type) {
				//When narrowing parameter types casts are often needed
				ensureExpected(opcode, Opcodes.CHECKCAST);
			}

			@Override
			public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
				if (valid) invalidate(); //Shouldn't see any of these
			}

			@Override
			public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
				if (valid) {
					if (seenMethod != null) {
						invalidate();
						return;
					}
					seenMethod = new EntryTriple(owner, name, descriptor);

					//Probably only want INVOKEVIRTUAL for non-interfaces
					ensureExpected(opcode, visitingInterface ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL);

					if (valid) {
						if (!targetBridge.owner.equals(owner)) {
							invalidate();
							return;
						}

						JarClassEntry classEntry = classes.getClass(owner, false);
						assert classEntry != null: "Unable to find class within classes: " + owner;

						JarMethodEntry bridged = classEntry.getMethod(name + descriptor);
						//assert bridged != null: "Unable to find method within class: " + owner + '/' + name + descriptor;
						if (bridged == null) {//Happens when an inherited method is claimed to be on owner instead
							invalidate();
							return;
						}

						//Expect some descriptor narrowing of bridged method compared to the bridge itself
						if (descriptor.equals(targetBridge.desc)) {
							invalidate(); //If the signatures are completely the same it's not a real bridge, more likely just an accessor
							return;
						}

						Type[] bridgedArgs = Type.getArgumentTypes(descriptor);
						if (bridgedArgs.length != args.length) {
							invalidate(); //Wrong number of arguments
							return;
						}

						for (int i = 0; i < bridgedArgs.length; i++) {
							if (!relatedTypes(bridgedArgs[i], args[i])) {
								invalidate(); //Not the same as, nor narrowing, the parameter type
								return;
							}
						}
						if (!relatedTypes(Type.getReturnType(descriptor), returnType)) {
							invalidate(); //Not the same as, nor narrowing, the return type
							return;
						}

						//The access of the bridged method should match the access of the bridge itself
						if ((targetBridge.access & (Modifier.PUBLIC | Modifier.PROTECTED | Modifier.PRIVATE)) != (bridged.getAccess() & (Modifier.PUBLIC | Modifier.PROTECTED | Modifier.PRIVATE))) {
							//"Inconsistent access between bridge: " + targetBridge.getID() + " (" + targetBridge.access + ") and target " + name + descriptor + " (" + bridged.getAccess() + ") in " + owner;
							invalidate();
							return;
						}
					}
				}
			}

			private boolean relatedTypes(Type type, Type potentialParent) {
				String parentName;
				if (type.equals(potentialParent) || "java/lang/Object".equals(parentName = potentialParent.getInternalName())) return true;

				//Arrays will only narrow from Object and primitives can't narrow from anything
				if (type.getSort() != Type.OBJECT) return false;

				JarClassEntry origin = classes.getClass(type.getInternalName(), false);
				if (origin == null) {
					assert classes.getClass(parentName, false) == null;
					return true; //Ehhhh probably
				}

				JarClassEntry target = classes.getClass(parentName, false);
				if (target == null) return true; //Ehhhh probably

				if (!target.isInterface()) {
					JarClassEntry parent = origin;
			    	do {
			    		parent = parent.getSuperClass(classes);
			    	} while (parent != null && parent != target);

			    	//Found the target as a parent class <=> parent not null
			    	return parent != null;
				} else {
			    	Deque<JarClassEntry> interfaces = new ArrayDeque<>(origin.getAllInterfaces(classes));

					JarClassEntry itf;
					while ((itf = interfaces.poll()) != null) {
						if (itf == target) {
							return true; //Found the right interface
						} else {
							interfaces.addAll(itf.getAllInterfaces(classes));
						}
					}

					return false; //Couldn't find any matching interfaces in the hierarchy
				}
			}

			@Override
			public void visitInvokeDynamicInsn(String name, String descriptor, Handle bootstrapMethodHandle, Object... bootstrapMethodArguments) {
				if (valid) invalidate(); //Shouldn't see any of these
			}

			@Override
			public void visitJumpInsn(int opcode, Label label) {
				if (valid) invalidate(); //Shouldn't see any of these
			}

			@Override
			public void visitLdcInsn(Object value) {
				if (valid) invalidate(); //Shouldn't see any of these
			}

			@Override
			public void visitIincInsn(int var, int increment) {
				if (valid) invalidate(); //Shouldn't see any of these
			}

			@Override
			public void visitTableSwitchInsn(int min, int max, Label defaultHandler, Label... labels) {
				if (valid) invalidate(); //Shouldn't see any of these
			}

			@Override
			public void visitLookupSwitchInsn(Label defaultHandler, int[] keys, Label[] labels) {
				if (valid) invalidate(); //Shouldn't see any of these
			}

			@Override
			public void visitMultiANewArrayInsn(String descriptor, int numDimensions) {
				if (valid) invalidate(); //Shouldn't see any of these
			}

			@Override
			public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
				if (valid) invalidate(); //Shouldn't see any of these
			}

			@Override
			public void visitMaxs(int maxStack, int maxLocals) {
				if (valid) {
					//maxStack should be the same size as maxLocals as all args need to be loaded in
					//There should be enough room in maxLocals for all the args too
					if (maxStack != maxLocals || maxLocals != argSize) invalidate();
				}
			}

			@Override
			public void visitEnd() {
				out: if (valid) {
					if (!seenReturn || seenMethod == null) {
						invalidate();
						break out;
					}

					for (boolean arg : seenArg) {
						if (!arg) {
							invalidate();
							break out;
						}
					}
				}

				targetBridge.onDetectionComplete(valid, valid ? seenMethod : null);
			}
		}
		private static final class MethodDetail {
			public final String owner, name, desc;
			public final int access;
			private Boolean couldBeBridge;
			private EntryTriple bridgedMethod;

			public MethodDetail(Method method) {
				owner = method.owner.getFullyQualifiedName();
				name = method.method.getName();
				desc = method.method.getDescriptor();
				access = method.method.getAccess();
			}

			public String getID() {
				return name + desc;
			}

			public EntryTriple asEntry() {
				return new EntryTriple(owner, name, desc);
			}

			void onDetectionComplete(boolean isProbablyBridge, EntryTriple bridgedMethod) {
				assert isProbablyBridge == (bridgedMethod != null): //Only want a bridgedMethod when isProbabyBridge is true
					"Inconsistently reported " + StitchUtil.memberString(asEntry()) + " to be a bridge for " + StitchUtil.memberString(bridgedMethod);

				couldBeBridge = isProbablyBridge;
				this.bridgedMethod = bridgedMethod;

				assert !couldBeBridge || Access.isSynthetic(access): "Found non-synthetic bridge: " + StitchUtil.memberString(asEntry()) + " => " + StitchUtil.memberString(bridgedMethod);
			}

			private void ensureSure() {
				if (couldBeBridge == null) throw new IllegalStateException("Bridge detector not run!");
			}

			public boolean isPotentiallyBridge() {
				ensureSure();
				return couldBeBridge;
			}

			public EntryTriple potentialBridge() {
				ensureSure();
				return bridgedMethod;
			}
		}
		private final Map<String, MethodDetail> methods = new HashMap<>();
		final ClassStorage classes;
		boolean visitingInterface;

		public BridgeDetector(ClassStorage classes, Method... methods) {
			super(Opcodes.ASM7);

			this.classes = classes;
			for (Method method : methods) {
				MethodDetail detail = new MethodDetail(method);

				this.methods.put(detail.getID(), detail);
			}
		}

		@Override
		public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
			for (MethodDetail method : methods.values()) {
				if (!method.owner.equals(name)) {//Don't want to silently fail for methods which definitely aren't in the class being visited
					throw new IllegalArgumentException("Bridge detector sent to visit wrong class, visiting " + name + " but looking for " + method.owner);
				}
			}

			visitingInterface = Modifier.isInterface(access);
		}

		@Override
		public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
			MethodDetail method = methods.get(name + descriptor);

			if (method != null) {
				assert method.access == access: "Expected method access flags of " + method.access + " but found " + access + " for " + method.owner + '/' + method.getID();
				assert method.desc.equals(descriptor);
				return new BridgeVisitor(method);
			} else {
				return null;
			}
		}

		public boolean foundAnyBridges() {
			return methods.values().stream().anyMatch(MethodDetail::isPotentiallyBridge);
		}

		public Stream<EntryTriple> foundBridges() {
			return methods.values().stream().filter(MethodDetail::isPotentiallyBridge).map(MethodDetail::asEntry);
		}

		public Stream<EntryTriple> foundBridged() {
			return methods.values().stream().filter(MethodDetail::isPotentiallyBridge).map(MethodDetail::potentialBridge);
		}

		public Map<EntryTriple, EntryTriple> bridgeMap() {
			return methods.values().stream().filter(MethodDetail::isPotentiallyBridge).collect(Collectors.toMap(MethodDetail::asEntry, MethodDetail::potentialBridge));
		}
	}
}