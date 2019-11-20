/*
 * Copyright (c) 2016, 2017, 2018, 2019 FabricMC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.fabricmc.stitch.commands;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

import org.objectweb.asm.Type;

import net.fabricmc.mappings.ClassEntry;
import net.fabricmc.mappings.EntryTriple;
import net.fabricmc.mappings.FieldEntry;
import net.fabricmc.mappings.Mappings;
import net.fabricmc.mappings.MappingsProvider;
import net.fabricmc.mappings.MethodEntry;
import net.fabricmc.stitch.Command;
import net.fabricmc.stitch.commands.CommandMergeTiny.TinyFile.ClassLine;
import net.fabricmc.stitch.commands.CommandMergeTiny.TinyFile.FieldLine;
import net.fabricmc.stitch.commands.CommandMergeTiny.TinyFile.MethodLine;

public class CommandMergeTiny extends Command {
	public CommandMergeTiny() {
		super("mergeTiny");
	}

	@Override
	public String getHelpString() {
		return "<input-a> <input-b> <output> [-c|--common-namespace <namespace>] [-h|--leave-holes]";
	}

	@Override
	public boolean isArgumentCountValid(int count) {
		return count >= 3;
	}

	private static abstract class TinyLine {
		public final String line;

		public TinyLine(String line) {
			this.line = line;
		}
	}
	public static class ContextLine extends TinyLine {
		public ContextLine(String line) {
			super(line);
		}
	}

	static class TinyFile {
		public class ClassLine extends TinyLine implements ClassEntry {
			private final String[] names;

			ClassLine(String line, String[] data, String[] namespaceList) {
				super(line);

				names = new String[namespaceList.length];
				for (int i = 0, end = Math.min(namespaceList.length, data.length - 1); i < end; i++) {
					if (data[i + 1].isEmpty()) continue; //Skip holes
					names[i] = data[i + 1];
				}

				assert Arrays.stream(names).filter(Objects::nonNull).noneMatch(String::isEmpty);
			}

			@Override
			public String get(String namespace) {
				return names[namespacesToIds.get(namespace)];
			}
		}
		public class FieldLine extends TinyLine implements FieldEntry {
			protected final EntryTriple nativeTriple;
			protected final String[] names;

			FieldLine(String line, String[] data, String[] namespaceList) {
				super(line);

				nativeTriple = new EntryTriple(data[1], data[3], data[2]);

				names = new String[namespaceList.length];
				for (int i = 0, end = Math.min(namespaceList.length, data.length - 3); i < end; i++) {
					if (data[3 + i].isEmpty()) continue; //Skip holes
					names[i] = data[3 + i];
				}

				assert Arrays.stream(names).filter(Objects::nonNull).noneMatch(String::isEmpty);
			}

			@Override
			public EntryTriple get(String namespace) {
				if (nativeNamespace.equals(namespace)) return nativeTriple;

				return new EntryTriple(remap(nativeTriple.getOwner(), namespace), names[namespacesToIds.get(namespace)], remapDesc(nativeTriple.getDesc(), namespace));
			}

			protected String remapDesc(String desc, String namespace) {
				Type type = Type.getType(desc);

				switch (type.getSort()) {
				case Type.ARRAY: {
					String out = remap(type.getElementType().getDescriptor(), namespace);

					for (int i = 0; i < type.getDimensions(); ++i) {
						out = '[' + out;
					}

					return out;
				}

				case Type.OBJECT: {
					String out = remap(type.getInternalName(), namespace);

					if (out != null) {
						return 'L' + out + ';';
					}
				}

				default:
					return desc;
				}
			}
		}
		public class MethodLine extends FieldLine implements MethodEntry {
			MethodLine(String line, String[] data, String[] namespaceList) {
				super(line, data, namespaceList);
			}

			@Override
			protected String remapDesc(String desc, String namespace) {
				if ("()V".equals(desc)) {
					return desc;
				}

				Type[] args = Type.getArgumentTypes(desc);
				StringBuilder out = new StringBuilder("(");
				for (int i = 0; i < args.length; i++) {
					out.append(super.remapDesc(args[i].getDescriptor(), namespace));
				}

				Type returnType = Type.getReturnType(desc);
				if (returnType == Type.VOID_TYPE) {
					return out.append(")V").toString();
				}

				return out.append(')').append(super.remapDesc(returnType.getDescriptor(), namespace)).toString();
			}
		}
		final Map<String, Integer> namespacesToIds = new HashMap<>();
		private final Map<String, ClassLine> nativeClassLines = new HashMap<>();
		public final String nativeNamespace;

		public final String firstLine;
		public final List<TinyLine> lines = new ArrayList<>();

		public TinyFile(Path file) throws IOException {
			try (BufferedReader reader = Files.newBufferedReader(file)) {
				String[] header = (firstLine = reader.readLine()).split("\t");
				if (header.length <= 1 || !header[0].equals("v1")) {
					throw new IOException("Invalid mapping version!");
				}

				String[] namespaceList = new String[header.length - 1];
				for (int i = 1; i < header.length; i++) {
					namespaceList[i - 1] = header[i];

					if (namespacesToIds.put(header[i], i - 1) != null) {
						throw new IOException("Duplicate namespace: " + header[i]);
					}
				}
				nativeNamespace = header[1];

				String line;
				while ((line = reader.readLine()) != null) {
					String[] splitLine = line.split("\t");

					if (splitLine.length >= 2) {
						switch (splitLine[0]) {
							case "CLASS":
								ClassLine tinyLine = new ClassLine(line, splitLine, namespaceList);
								lines.add(tinyLine);
								nativeClassLines.put(tinyLine.get(nativeNamespace), tinyLine);
								break;
	
							case "FIELD":
								lines.add(new FieldLine(line, splitLine, namespaceList));
								break;
	
							case "METHOD":
								lines.add(new MethodLine(line, splitLine, namespaceList));
								break;
						}
					} else {
						lines.add(new ContextLine(line));
					}
				}
			}
		}

		String remap(String className, String namespace) {
			ClassLine line = nativeClassLines.get(className);

			if (line != null) {
				String name = line.get(namespace);
				if (name != null) return name;
			}

			return className;
		}

		public Set<String> getNamespaces() {
			return namespacesToIds.keySet();
		}
	}
	@Override
	public void run(String[] args) throws Exception {
		String commonNamespace = null;
		boolean leaveHoles = false;
		for (int i = 3; i < args.length; i++) {
            switch (args[i].toLowerCase(Locale.ROOT)) {
                case "-c":
                case "--common-namespace":
                	commonNamespace = args[++i];
                    break;

                case "-h":
                case "--leave-holes":
                	leaveHoles = true;
                	break;
            }
        }

		run(Paths.get(args[0]), Paths.get(args[1]), Paths.get(args[2]), commonNamespace, leaveHoles);
	}

	public static void run(Path firstInput, Path secondInput, Path output, String commonNamespace, boolean leaveHoles) throws IOException {
		TinyFile inputA = new TinyFile(firstInput);

		Mappings inputB; 
		try (InputStream in = Files.newInputStream(secondInput)) {
        	inputB = MappingsProvider.readTinyMappings(in);
        }

		if (commonNamespace == null) {
			List<String> namespaces = new ArrayList<>(inputA.getNamespaces());
			namespaces.retainAll(inputB.getNamespaces());

			switch (namespaces.size()) {
			case 0:
				throw new IllegalArgumentException("No common namespaces between inputs, only found A: " + inputA.getNamespaces() + ", B: " + inputB.getNamespaces());

			case 1:
				commonNamespace = namespaces.get(0);
				break;

			default:
				throw new IllegalArgumentException("Multiple common namespaces between inputs: " + namespaces + ", specify the desired common namespace via -c");
			}
		} else {
			if (!inputA.getNamespaces().contains(commonNamespace)) {
				throw new IllegalArgumentException("Unable to find specified common namespace in A input, only found " + inputA.getNamespaces());
			}
			if (!inputB.getNamespaces().contains(commonNamespace)) {
				throw new IllegalArgumentException("Unable to find specified common namespace in B input, only found " + inputB.getNamespaces());
			}
		}

		String commonNamespaceTarget = commonNamespace;
		Map<String, ClassEntry> commonToAllB = commonToAll(inputB.getClassEntries(), entry -> entry.get(commonNamespaceTarget));

		Map<EntryTriple, MethodEntry> classToMethod = commonToAll(inputB.getMethodEntries(), method -> method.get(commonNamespaceTarget));
		Map<EntryTriple, FieldEntry> classToField = commonToAll(inputB.getFieldEntries(), field -> field.get(commonNamespaceTarget));

		try (BufferedWriter writer = Files.newBufferedWriter(output, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
			List<String> extraNamespaces = new ArrayList<>(inputB.getNamespaces());
			extraNamespaces.removeAll(inputA.getNamespaces());
			if (extraNamespaces.isEmpty()) throw new IllegalArgumentException("No additional namespaces to merge from B");

			writer.write(inputA.firstLine);
			for (String namespace : extraNamespaces) {
				writer.write('\t');
				writer.write(namespace);
			}
			writer.newLine();

			for (TinyLine line : inputA.lines) {
				Class<? extends TinyLine> lineType = line.getClass();

				if (lineType == ContextLine.class) {
					writer.write(line.line);
					writer.newLine();
				} else if (lineType == ClassLine.class) {
					writer.write(line.line);

					String commonName = ((ClassLine) line).get(commonNamespaceTarget);
					ClassEntry commonEntry = commonToAllB.get(commonName);

					for (String namespace : extraNamespaces) {
						writer.write('\t');

						if (commonEntry != null) {
							String name = commonEntry.get(namespace);
							if (name != null) writer.write(name);
						} else if (!leaveHoles) {
							writer.write(commonName);
						}
					}

					writer.newLine();
				} else if (lineType == MethodLine.class) {
					writer.write(line.line);

					EntryTriple commonName = ((MethodLine) line).get(commonNamespaceTarget);
					MethodEntry commonEntry = classToMethod.get(commonName);

					for (String namespace : extraNamespaces) {
						writer.write('\t');

						if (commonEntry != null) {
							EntryTriple entry = commonEntry.get(namespace);
							if (entry != null) writer.write(entry.getName());
						} else if (!leaveHoles) {
							writer.write(commonName.getName());
						}
					}

					writer.newLine();
				} else if (lineType == FieldLine.class) {
					writer.write(line.line);

					EntryTriple commonName = ((FieldLine) line).get(commonNamespaceTarget);
					FieldEntry commonEntry = classToField.get(commonName);

					for (String namespace : extraNamespaces) {
						writer.write('\t');

						if (commonEntry != null) {
							EntryTriple entry = commonEntry.get(namespace);
							if (entry != null) writer.write(entry.getName());
						} else if (!leaveHoles) {
							writer.write(commonName.getName());
						}
					}

					writer.newLine();					
				} else {
					throw new IllegalStateException("Unexpected tiny line: " + line + " (" + lineType + ')');
				}
			}
		}
	}

	private static <K, T> Map<K, T> commonToAll(Iterable<? extends T> entries, Function<T, K> converter) {
		Map<K, T> map = new HashMap<>();

		for (T entry : entries) {
			K name = converter.apply(entry);
			if (name == null) throw new IllegalArgumentException("Common mapping target has holes");
			map.put(name, entry);
		}

		return map;
	}
}