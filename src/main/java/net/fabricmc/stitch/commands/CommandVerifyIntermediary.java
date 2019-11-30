package net.fabricmc.stitch.commands;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.objectweb.asm.Type;

import net.fabricmc.mappings.EntryTriple;
import net.fabricmc.mappings.FieldEntry;
import net.fabricmc.mappings.Mappings;
import net.fabricmc.mappings.MappingsProvider;
import net.fabricmc.mappings.MethodEntry;
import net.fabricmc.stitch.Command;

public class CommandVerifyIntermediary extends Command {
	public CommandVerifyIntermediary() {
		super("verifyIntermediary");
	}

	@Override
	public String getHelpString() {
		return "<mapping-file> [namespaces...]";
	}

	@Override
	public boolean isArgumentCountValid(int count) {
		return count >= 1;
	}

	@Override
	public void run(String[] args) throws Exception {
		run(Paths.get(args[0]), Arrays.copyOfRange(args, 1, args.length));
	}

	public static void run(Path mappingFile, String... namespaces) throws IOException {
		Mappings mappings;
		try (InputStream in = Files.newInputStream(mappingFile)) {
			mappings = MappingsProvider.readTinyMappings(in);
		}

		if (namespaces == null || namespaces.length == 0) {
			namespaces = mappings.getNamespaces().toArray(new String[0]);
		}

		for (String namespace : namespaces) {
			Set<String> classes = mappings.getClassEntries().stream().map(entry -> entry.get(namespace)).collect(Collectors.toSet());
			//A few classes could have obfuscated members but not an obfuscated name, thus we'll want to pull from any member's owners that don't seem to be an Intermediary name 
			mappings.getMethodEntries().stream().map(entry -> entry.get(namespace)).filter(Objects::nonNull).map(EntryTriple::getOwner).filter(owner -> !owner.startsWith("net/minecraft/class_")).forEach(classes::add);
			mappings.getFieldEntries().stream().map(entry -> entry.get(namespace)).filter(Objects::nonNull).map(EntryTriple::getOwner).filter(owner -> !owner.startsWith("net/minecraft/class_")).forEach(classes::add);

			findDuplicates(classes, duplicate -> {
				System.out.println("Duplicate declarations of class mapping in " + namespace + ": " + duplicate);
			});

			int methods = 0, badMethods = 0;
			for (MethodEntry entry : mappings.getMethodEntries()) {
				EntryTriple method = entry.get(namespace);
				if (method == null) continue; //Hole

				methods++;
				if (!isValid(classes, Stream.concat(Arrays.stream(Type.getArgumentTypes(method.getDesc())), Stream.of(Type.getReturnType(method.getDesc()))))) {
					System.out.println("Descriptor for " + memberString(method) + " is invalid");
					badMethods++;
				}
			}

			findDuplicates(mappings.getMethodEntries().stream().map(entry -> entry.get(namespace)).filter(Objects::nonNull).collect(Collectors.toList()), duplicate -> {
				System.out.println("Duplicate declarations of method mapping in " + namespace + ": " + memberString(duplicate));
			});

			int fields = 0, badFields = 0;
			for (FieldEntry entry : mappings.getFieldEntries()) {
				EntryTriple field = entry.get(namespace);
				if (field == null) continue; //Hole

				fields++;
				if (!isValid(classes, Stream.of(Type.getType(field.getDesc())))) {
					System.out.println("Descriptor for " + memberString(field) + " is invalid");
					badFields++;
				}
			}

			findDuplicates(mappings.getFieldEntries().stream().map(entry -> entry.get(namespace)).filter(Objects::nonNull).collect(Collectors.toList()), duplicate -> {
				System.out.println("Duplicate declarations of field mapping in " + namespace + ": " + memberString(duplicate));
			});

			System.out.printf("%nFound %d/%d incorrect methods and %d/%d incorrect fields for %s%n%n", badMethods, methods, badFields, fields, namespace);
		}
	}

	private static <T> void findDuplicates(Collection<T> entries, Consumer<T> duplicateAcceptor) {
		entries.stream().filter(e -> Collections.frequency(entries, e) > 1).distinct().forEach(duplicateAcceptor);
	}

	static String memberString(EntryTriple triple) {
		return triple.getOwner() + '/' + triple.getName() + triple.getDesc();
	}

	private static boolean isValid(Set<String> classes, Stream<Type> types) {
		return types.filter(type -> {
			switch (type.getSort()) {
				case Type.ARRAY:
					return type.getElementType().getSort() == Type.OBJECT;

				case Type.OBJECT:
					return true;

				default:
					return false;
			}
		}).map(type -> (type.getSort() == Type.ARRAY ? type.getElementType() : type).getInternalName()).filter(type -> type.startsWith("net/minecraft/") || type.startsWith("argo/") || type.indexOf('/') <= 0).allMatch(classes::contains);
	}
}