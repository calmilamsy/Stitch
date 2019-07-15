package net.fabricmc.stitch.commands;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Queue;
import java.util.stream.Stream;

import net.fabricmc.mappings.EntryTriple;
import net.fabricmc.mappings.Mappings;
import net.fabricmc.mappings.MappingsProvider;
import net.fabricmc.stitch.Command;

public class CommandRenameEnigma extends Command {
	private static class MappingFile {
		public final Path path;
		public final String name;
		private String mappedName;
		private final Map<String, String> methods = new HashMap<>();
		private final Map<String, List<String>> methodArgs = new LinkedHashMap<>();
		private final List<String> fields = new ArrayList<>();
		private final List<MappingFile> nests = new ArrayList<>();

		public MappingFile(Path path, String name) {
			assert path != null;
			assert name != null;
			this.path = path;
			this.name = name;
		}

		public MappingFile setName(String mappedName) {
			assert this.mappedName == null;
			this.mappedName = mappedName;
			return this;
		}

		public boolean hasName() {
			return mappedName != null;
		}

		public String getName() {
			assert hasName();
			return mappedName;
		}

		public void addNest(MappingFile file) {
			nests.add(file);
		}

		public List<MappingFile> getNests() {
			return Collections.unmodifiableList(nests);
		}

		public void addMethod(String name, String desc, String mappedName) {
			if (mappedName != null) methods.put(name + ' ' + desc, mappedName);
			methodArgs.put(name + ' ' + desc, new ArrayList<>());
		}

		public void addMethodArg(String methodName, String methodDesc, int index, String name) {
			methodArgs.get(methodName + ' ' + methodDesc).add(index + " " + name);
		}

		public void addField(String name, String desc, String mappedName) {
			fields.add(name + ' ' + mappedName + ' ' + desc);
		}

		public void merge(MappingFile into) {
			assert name.equals(into.name);
			assert methods.isEmpty();
			assert methodArgs.isEmpty();
			assert fields.isEmpty();

			for (MappingFile nest : nests) {
				into.addNest(nest);
			}
		}

		public void dump(BufferedWriter writer) throws IOException {
			dump(writer, 0);
		}

		protected void dump(BufferedWriter writer, int indent) throws IOException {
			indent(writer, indent);
			writer.write("CLASS ");
			writer.write(inner(name));
			if (mappedName != null) {
				writer.write(' ');
				writer.write(inner(mappedName));
			}
			writer.newLine();

			for (MappingFile nest : nests) {
				nest.dump(writer, indent + 1);
			}

			for (String field : fields) {
				indent(writer, indent + 1);
				writer.write("FIELD ");
				writer.write(field);
				writer.newLine();
			}

			for (Entry<String, List<String>> entry : methodArgs.entrySet()) {
				String nameDesc = entry.getKey();
				List<String> args = entry.getValue();

				int split = nameDesc.indexOf(' ');
				String name = nameDesc.substring(0, split++);
				String desc = nameDesc.substring(split);

				indent(writer, indent + 1);
				writer.write("METHOD ");
				writer.write(name);
				writer.write(' ');

				String mappedName = methods.get(nameDesc);
				if (mappedName != null) {
					writer.write(mappedName);
					writer.write(' ');
				}

				writer.write(desc);
				writer.newLine();

				for (String arg : args) {
					indent(writer, indent + 2);
					writer.write("ARG ");
					writer.write(arg);
					writer.newLine();
				}
			}
		}

		public static String inner(String className) {
			int end = className.lastIndexOf('$');
			return end <= 0 ? className : className.substring(end + 1);
		}

		public static void indent(BufferedWriter writer, int amount) throws IOException {
			for (int i = 0; i < amount; i++) writer.write('\t');
		}
	}
	
	public CommandRenameEnigma() {
		super("renameEnigma");
	}

	@Override
	public String getHelpString() {
		return "<mapping-file> <origin-naming> <target-naming> <mappings-dir> [-d|--dry-run]";
	}

	@Override
	public boolean isArgumentCountValid(int count) {
		return count == 4 || count == 5;
	}

	@Override
	public void run(String[] args) throws Exception {
		GenMap map = new GenMap();
		try (FileInputStream in = new FileInputStream(new File(args[0]))) {
			Mappings mappings = MappingsProvider.readTinyMappings(in);

			map.load(mappings, args[1], args[2]);
		}

		boolean dryRun = false;
		for (int i = 4; i < args.length; i++) {
			if (args[i].startsWith("--")) {
				switch (args[i].toLowerCase(Locale.ROOT)) {
				case "-d":
				case "--dry-run":
					dryRun = true;
					break;
				}
			}
		}

		Map<String, MappingFile> files = new HashMap<>();

		Path root = Paths.get(args[3]);
		try (Stream<Path> stream = Files.walk(root, FileVisitOption.FOLLOW_LINKS)) {
			stream.filter(file -> Files.isRegularFile(file) && file.getFileName().toString().endsWith(".mapping")).forEach(file -> {
				MappingFile mapping = readEnigmaFile(root, file, map);
				int split = mapping.name.indexOf('$');
				if (split > 0) {
					String rootMapping = mapping.name.substring(0, split);

					MappingFile existing = files.get(rootMapping);
					if (existing != null) {
						existing.addNest(mapping);
					} else {
						MappingFile temp = new MappingFile(root.resolve(rootMapping + ".mapping"), rootMapping);
						temp.addNest(mapping);
						files.put(rootMapping, temp);
					}
				} else {
					MappingFile existing = files.get(mapping.name);
					if (existing != null) existing.merge(mapping);
					files.put(mapping.name, mapping);
				}
			});
		} catch (UncheckedIOException e) {
			throw e.getCause();
		}

		if (dryRun) {
			for (MappingFile file : files.values()) {
				System.out.println(file.name + (file.hasName() ? " (" + file.getName() + ')' : ""));

				List<MappingFile> nests = new ArrayList<>(file.getNests());
				while (!nests.isEmpty()) {
					MappingFile nest = nests.remove(0);
					System.out.println(nest.name + (nest.hasName() ? " (" + nest.getName() + ')' : ""));
					nests.addAll(0, nest.getNests());
				}

				System.out.println();
			}
		} else {
			Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
				@Override
				public FileVisitResult visitFile(Path path, BasicFileAttributes basicFileAttributes) throws IOException {
					Files.delete(path);
					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult postVisitDirectory(Path path, IOException e) throws IOException {
					Files.delete(path);
					return FileVisitResult.CONTINUE;
				}
			});

			for (MappingFile file : files.values()) {
				Files.createDirectories(file.path.getParent());

				try (BufferedWriter writer = Files.newBufferedWriter(file.path)) {
					file.dump(writer);
				}
			}
		}
	}

	private static String trimExtension(String str) {
		assert str.length() > ".mapping".length();
	    return str.substring(0, str.length() - ".mapping".length());
	}

	private static MappingFile readEnigmaFile(Path root, Path file, GenMap map) {
		Path out; {
			String fileName = trimExtension(root.relativize(file).toString().replace('\\', '/'));
			String mappedName = map.getClass(fileName);
			if (mappedName == null) mappedName = fileName;

			out = root.resolve(mappedName + ".mapping");
		}

		//System.out.println("Doing " + file + " => " + out);

		try (BufferedReader reader = Files.newBufferedReader(file)) {
			String line;
			Queue<String> contextStack = Collections.asLifoQueue(new ArrayDeque<>());
			Queue<String> contextNamedStack = Collections.asLifoQueue(new ArrayDeque<>());
			Deque<MappingFile> activeMap = new ArrayDeque<>();
			int indent = 0;

			while ((line = reader.readLine()) != null) {
				if (line.trim().isEmpty()) continue;

				int newIndent = 0;
				while (newIndent < line.length() && line.charAt(newIndent) == '\t') newIndent++;
				int indentChange = newIndent - indent;

				if (indentChange != 0) {
					if (indentChange < 0) {
						for (int i = 0; i < -indentChange; i++) {
							String old = contextStack.remove();
							contextNamedStack.remove();

							if (old.charAt(0) == 'C') {
								activeMap.removeLast();
								assert !activeMap.isEmpty(): "Emptied " + file;								
							}
						}

						indent = newIndent;
					} else {
						throw new IOException("Invalid enigma line (invalid indentation change): " + line);
					}
				}

				line = line.substring(indent);
				String[] parts = line.split(" ");

				switch (parts[0]) {
				case "CLASS":
					if (parts.length < 2 || parts.length > 3) throw new IOException("Invalid enigma line (missing/extra columns): " + line);
					String obfName = parts[1];
					if (indent >= 1 && obfName.contains("/")) {//Some inner classes carry the named outer class, others the obf'd outer class
						int split = obfName.lastIndexOf('$');
						assert split > 2; //Should be at least a/b$c
						String context = contextStack.peek();
						if (context == null || context.charAt(0) != 'C') throw new IOException("Invalid enigma line (named inner class without outer class name): " + line);
						obfName = context.substring(1) + '$' + obfName.substring(split + 1);
					}
					contextStack.add('C' + obfName);
					indent++;
					if (parts.length == 3) {
						String className;
						if (indent > 1) {//If we're an indent in, we're an inner class so want the outer classes's name
							StringBuilder classNameBits = new StringBuilder(parts[2]);
							String context = contextNamedStack.peek();
							if (context == null || context.charAt(0) != 'C') throw new IOException("Invalid enigma line (named inner class without outer class name): " + line);
							//Named inner classes shouldn't ever carry the outer class's package + name
							assert !parts[2].startsWith(context.substring(1)): "Pre-prefixed enigma class name: " + parts[2];
							classNameBits.insert(0, '$');
							classNameBits.insert(0, context.substring(1));
							className = classNameBits.toString();
						} else {
							className = parts[2];
						}
						contextNamedStack.add('C' + className);

						MappingFile current = !activeMap.isEmpty() ? activeMap.peekLast() : null;
						String mappedName = map.getClass(obfName);
						activeMap.add(new MappingFile(out, mappedName != null ? mappedName : obfName).setName(className));
						if (current != null) current.addNest(activeMap.peekLast());
					} else {
						assert parts.length == 2;
						contextNamedStack.add('C' + obfName); //No name, but we still need something to avoid underflowing

						MappingFile current = !activeMap.isEmpty() ? activeMap.peekLast() : null;
						String mappedName = map.getClass(obfName);
						activeMap.add(new MappingFile(out, mappedName != null ? mappedName : obfName));
						if (current != null) current.addNest(activeMap.peekLast());
					}
					break;
				case "METHOD": {
					if (parts.length < 3 || parts.length > 4) throw new IOException("Invalid enigma line (missing/extra columns): " + line);
					if (!parts[parts.length - 1].startsWith("(")) throw new IOException("Invalid enigma line (invalid method desc): " + line);
					String context = contextStack.peek();
					if (context == null || context.charAt(0) != 'C') throw new IOException("Invalid enigma line (method without class): " + line);
					contextStack.add('M' + parts[1] + parts[parts.length - 1]);
					indent++;
					if (parts.length == 4) {
						EntryTriple mappedMethod = map.getMethod(context.substring(1), parts[1], parts[3]);
						activeMap.peekLast().addMethod(mappedMethod.getName(), mappedMethod.getDesc(), parts[2]);

						contextNamedStack.add('M' + parts[2]);
					} else {
						assert parts.length == 3;

						EntryTriple mappedMethod = map.getMethod(context.substring(1), parts[1], parts[2]);
						if (mappedMethod == null) mappedMethod = new EntryTriple(context.substring(1), parts[1], parts[2]);
						activeMap.peekLast().addMethod(mappedMethod.getName(), mappedMethod.getDesc(), null);

						contextNamedStack.add('M' + parts[1]); //No name, but we still need something to avoid underflowing
					}
					break;
				}
				case "ARG":
				case "VAR": {
					if (parts.length != 3) throw new IOException("Invalid enigma line (missing/extra columns): " + line);
					String methodContext = contextStack.poll();
					if (methodContext == null || methodContext.charAt(0) != 'M') throw new IOException("Invalid enigma line (arg without method): " + line);
					String classContext = contextStack.peek();
					if (classContext == null || classContext.charAt(0) != 'C') throw new IllegalStateException();
					contextStack.add(methodContext);
					int methodDescStart = methodContext.indexOf('(');
					assert methodDescStart != -1;

					String srcClsName = classContext.substring(1);
					String srcMethodName = methodContext.substring(1, methodDescStart);
					String srcMethodDesc = methodContext.substring(methodDescStart);
					int index = Integer.parseInt(parts[1]);
					String name = parts[2];

					EntryTriple mappedMethod = map.getMethod(srcClsName, srcMethodName, srcMethodDesc);
					if (mappedMethod == null) mappedMethod = new EntryTriple(srcClsName, srcMethodName, srcMethodDesc);
					activeMap.peekLast().addMethodArg(mappedMethod.getName(), mappedMethod.getDesc(), index, name);
					break;
				}
				case "FIELD":
					if (parts.length != 4) throw new IOException("Invalid enigma line (missing/extra columns): " + line);
					String context = contextStack.peek();
					if (context == null || context.charAt(0) != 'C') throw new IOException("Invalid enigma line (field without class): " + line);

					EntryTriple mappedField = map.getField(context.substring(1), parts[1], parts[3]);
					activeMap.peekLast().addField(mappedField.getName(), mappedField.getDesc(), parts[2]);
					break;
				default:
					throw new IOException("Invalid enigma line (unknown type): " + line);
				}
			}

			return activeMap.getFirst();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}