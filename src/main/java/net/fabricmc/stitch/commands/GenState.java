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

import net.fabricmc.mappings.EntryTriple;
import net.fabricmc.mappings.Mappings;
import net.fabricmc.mappings.MappingsProvider;
import net.fabricmc.stitch.representation.*;
import net.fabricmc.stitch.util.MatcherUtil;
import net.fabricmc.stitch.util.Pair;
import net.fabricmc.stitch.util.StitchUtil;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.Remapper;

import java.io.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

class GenState {
	private class InterRemapper extends Remapper {
		private final ClassStorage storage;

		public InterRemapper(ClassStorage storage) {
			this.storage = storage;
		}

		@Override
		public String map(String internalName) {
			JarClassEntry entry = storage.getClass(internalName, false);
			return entry != null ? getClassName(storage, entry, targetNamespace) : internalName;
		}

		@Override
		public String mapMethodName(String owner, String name, String descriptor) {
			throw new UnsupportedOperationException();
		}

		@Override
		public String mapFieldName(String owner, String name, String descriptor) {
			throw new UnsupportedOperationException();
		}
	}
	
    private final Map<String, Integer> counters = new HashMap<>();
    private final Map<AbstractJarEntry, Integer> values = new IdentityHashMap<>();
    private final GenMap server, client;
    private GenMap oldToIntermediary, newToOld;
    private GenMap newToIntermediary;
    private boolean interactive = true;
    private boolean keepGlue = false;
    private Scanner scanner = new Scanner(System.in);

    private String targetNamespace = "net/minecraft/";
    private final List<Pattern> obfuscatedPatterns = new ArrayList<Pattern>();

    public GenState(Mappings realMappings) {
        this.obfuscatedPatterns.add(Pattern.compile("^[^/]*$")); // Default obfuscation. Minecraft classes without a package are obfuscated.

        server = new GenMap();
        server.load(realMappings, "glue", "server");
        client = new GenMap();
        client.load(realMappings, "glue", "client");
    }

    public void disableInteractive() {
        interactive = false;
    }

    public String next(AbstractJarEntry entry, String name) {
        return name + "_" + values.computeIfAbsent(entry, (e) -> {
            int v = counters.getOrDefault(name, 1);
            counters.put(name, v + 1);
            return v;
        });
    }

    public void setTargetNamespace(final String namespace) {
        if (namespace.lastIndexOf("/") != (namespace.length() - 1))
            this.targetNamespace = namespace + "/";
        else
            this.targetNamespace = namespace;
    }

    public void clearObfuscatedPatterns() {
        this.obfuscatedPatterns.clear();
    }

    public void addObfuscatedPattern(String regex) throws PatternSyntaxException {
        this.obfuscatedPatterns.add(Pattern.compile(regex));
    }

    public void keepGlue() {
    	keepGlue = true;
    }

    public void setCounter(String key, int value) {
        counters.put(key, value);
    }

    public Map<String, Integer> getCounters() {
        return Collections.unmodifiableMap(counters);
    }

    public void generate(File file, JarRootEntry jarEntry, JarRootEntry jarOld) throws IOException {
        if (file.exists()) {
            System.err.println("Target file exists - loading...");
            newToIntermediary = new GenMap();
            try (FileInputStream inputStream = new FileInputStream(file)) {
                newToIntermediary.load(
                        MappingsProvider.readTinyMappings(inputStream),
                        "glue",
                        "intermediary"
                );
            }
        }

        try (FileWriter fileWriter = new FileWriter(file)) {
            try (BufferedWriter writer = new BufferedWriter(fileWriter)) {
            	writer.write("v1\tintermediary");
            	if (keepGlue) writer.write("\tglue");
                writer.write("\tserver\tclient\n");

                for (JarClassEntry c : jarEntry.getClasses()) {
                    addClass(writer, c, jarOld, jarEntry, this.targetNamespace);
                }

                for (Map.Entry<String, Integer> counter : counters.entrySet()) {
                    writer.write("# INTERMEDIARY-COUNTER " + counter.getKey() + " " + counter.getValue() + "\n");
                }
            }
        }
    }

    public static boolean isMappedClass(ClassStorage storage, JarClassEntry c) {
        return !c.isAnonymous();
    }

    public static boolean isMappedField(ClassStorage storage, JarClassEntry c, JarFieldEntry f) {
        return f.getName().startsWith("field_");
    }

    public static boolean isMappedMethod(ClassStorage storage, JarClassEntry c, JarMethodEntry m) {
        return m.getName().startsWith("method_") && m.isSource(storage, c);
    }

    private String getClassName(ClassStorage storage, JarClassEntry c, String translatedPrefix) {
    	if (!obfuscatedPatterns.stream().anyMatch(p -> p.matcher(c.getName()).matches())) {
    		return c.getFullyQualifiedName();
    	} else {
    		String className;
    		if (!isMappedClass(storage, c)) {
                className = c.getName();
            } else {
                className = null;

                if (newToIntermediary != null) {
                    String findName = newToIntermediary.getClass(c.getFullyQualifiedName());
                    if (findName != null) {
                        String[] r = findName.split("\\$");
                        className = r[r.length - 1];
                        if (r.length == 1) {
                            translatedPrefix = "";
                        }
                    }
                }

                if (className == null && newToOld != null) {
                    String findName = newToOld.getClass(c.getFullyQualifiedName());
                    if (findName != null) {
                        findName = oldToIntermediary.getClass(findName);
                        if (findName != null) {
                            String[] r = findName.split("\\$");
                            className = r[r.length - 1];
                            if (r.length == 1) {
                                translatedPrefix = "";
                            }

                        }
                    }
                }

                if (className == null) {
                    className = next(c, "class");
                }
            }

    		return translatedPrefix + className;
    	}
    }
    
    @Nullable
    private String getFieldName(ClassStorage storage, JarClassEntry c, JarFieldEntry f) {
        if (!isMappedField(storage, c, f)) {
            return null;
        }

        if (newToIntermediary != null) {
            EntryTriple findEntry = newToIntermediary.getField(c.getFullyQualifiedName(), f.getName(), f.getDescriptor());
            if (findEntry != null) {
                return findEntry.getName();
            }
        }

        if (newToOld != null) {
            EntryTriple findEntry = newToOld.getField(c.getFullyQualifiedName(), f.getName(), f.getDescriptor());
            if (findEntry != null) {
                findEntry = oldToIntermediary.getField(findEntry);
                if (findEntry != null) {
                    return findEntry.getName();
                }
            }
        }

        return next(f, "field");
    }

    private final Map<JarMethodEntry, String> methodNames = new IdentityHashMap<>();

    private String getPropagation(ClassStorage storage, JarClassEntry classEntry) {
        if (classEntry == null) {
            return "";
        }

        StringBuilder builder = new StringBuilder(classEntry.getFullyQualifiedName());
        List<String> strings = new ArrayList<>();
        String scs = getPropagation(storage, classEntry.getSuperClass(storage));
        if (!scs.isEmpty()) {
            strings.add(scs);
        }

        for (JarClassEntry ce : classEntry.getInterfaces(storage)) {
            scs = getPropagation(storage, ce);
            if (!scs.isEmpty()) {
                strings.add(scs);
            }
        }

        if (!strings.isEmpty()) {
            builder.append("<-");
            if (strings.size() == 1) {
                builder.append(strings.get(0));
            } else {
                builder.append("[");
                builder.append(StitchUtil.join(",", strings));
                builder.append("]");
            }
        }

        return builder.toString();
    }

    private String getNamesListEntry(ClassStorage storage, JarClassEntry classEntry) {
        StringBuilder builder = new StringBuilder(getPropagation(storage, classEntry));
        if (classEntry.isInterface()) {
            builder.append("(itf)");
        }

        return builder.toString();
    }

    private Set<JarMethodEntry> findNames(ClassStorage storageOld, ClassStorage storageNew, JarClassEntry c, JarMethodEntry m, Map<String, Set<String>> names) {
        Set<JarMethodEntry> allEntries = new HashSet<>();
        findNames(storageOld, storageNew, c, m, names, allEntries);
        return allEntries;
    }

    private void findNames(ClassStorage storageOld, ClassStorage storageNew, JarClassEntry c, JarMethodEntry m, Map<String, Set<String>> names, Set<JarMethodEntry> usedMethods) {
        if (!usedMethods.add(m)) {
            return;
        }

        String suffix = "." + m.getName() + m.getDescriptor();

        if ((m.getAccess() & Opcodes.ACC_BRIDGE) != 0) {
            suffix += "(bridge)";
        }

        List<JarClassEntry> ccList = m.getMatchingEntries(storageNew, c);

        for (JarClassEntry cc : ccList) {
            EntryTriple findEntry = null;
            if (newToIntermediary != null) {
                findEntry = newToIntermediary.getMethod(cc.getFullyQualifiedName(), m.getName(), m.getDescriptor());
                if (findEntry != null) {
                    names.computeIfAbsent(findEntry.getName(), (s) -> new TreeSet<>()).add(getNamesListEntry(storageNew, cc) + suffix);
                }
            }

            if (findEntry == null && newToOld != null) {
                findEntry = newToOld.getMethod(cc.getFullyQualifiedName(), m.getName(), m.getDescriptor());
                if (findEntry != null) {
                    EntryTriple newToOldEntry = findEntry;
                    findEntry = oldToIntermediary.getMethod(newToOldEntry);
                    if (findEntry != null) {
                        names.computeIfAbsent(findEntry.getName(), (s) -> new TreeSet<>()).add(getNamesListEntry(storageNew, cc) + suffix);
                    } else {
                        // more involved...
                        JarClassEntry oldBase = storageOld.getClass(newToOldEntry.getOwner(), false);
                        if (oldBase != null) {
                            JarMethodEntry oldM = oldBase.getMethod(newToOldEntry.getName() + newToOldEntry.getDesc());
                            List<JarClassEntry> cccList = oldM.getMatchingEntries(storageOld, oldBase);

                            for (JarClassEntry ccc : cccList) {
                                findEntry = oldToIntermediary.getMethod(ccc.getFullyQualifiedName(), oldM.getName(), oldM.getDescriptor());
                                if (findEntry != null) {
                                    names.computeIfAbsent(findEntry.getName(), (s) -> new TreeSet<>()).add(getNamesListEntry(storageOld, ccc) + suffix);
                                }
                            }
                        }
                    }
                }
            }
        }

        for (JarClassEntry mc : ccList) {
            for (Pair<JarClassEntry, String> pair : mc.getRelatedMethods(m)) {
                findNames(storageOld, storageNew, pair.getLeft(), pair.getLeft().getMethod(pair.getRight()), names, usedMethods);
            }
        }
    }

    @Nullable
    private String getMethodName(ClassStorage storageOld, ClassStorage storageNew, JarClassEntry c, JarMethodEntry m) {
        if (!isMappedMethod(storageNew, c, m)) {
            return null;
        }

        if (methodNames.containsKey(m)) {
            return methodNames.get(m);
        }

        if (newToOld != null || newToIntermediary != null) {
            Map<String, Set<String>> names = new HashMap<>();
            Set<JarMethodEntry> allEntries = findNames(storageOld, storageNew, c, m, names);
            for (JarMethodEntry mm : allEntries) {
                if (methodNames.containsKey(mm)) {
                    return methodNames.get(mm);
                }
            }

            if (names.size() > 1) {
                System.out.println("Conflict detected - matched same target name!");
                List<String> nameList = new ArrayList<>(names.keySet());
                Collections.sort(nameList);

                for (int i = 0; i < nameList.size(); i++) {
                    String s = nameList.get(i);
                    System.out.println((i+1) + ") " + s + " <- " + StitchUtil.join(", ", names.get(s)));
                }

                if (!interactive) {
                    throw new RuntimeException("Conflict detected!");
                }

                while (true) {
                    String cmd = scanner.nextLine();
                    int i;
                    try {
                        i = Integer.parseInt(cmd);
                    } catch (NumberFormatException e) {
                        e.printStackTrace();
                        continue;
                    }

                    if (i >= 1 && i <= nameList.size()) {
                        for (JarMethodEntry mm : allEntries) {
                            methodNames.put(mm, nameList.get(i - 1));
                        }
                        System.out.println("OK!");
                        return nameList.get(i - 1);
                    }
                }
            } else if (names.size() == 1) {
                String s = names.keySet().iterator().next();
                for (JarMethodEntry mm : allEntries) {
                    methodNames.put(mm, s);
                }
                return s;
            }
        }

        return next(m, "method");
    }

    private void addClass(BufferedWriter writer, JarClassEntry c, ClassStorage storageOld, ClassStorage storage, String translatedPrefix) throws IOException {
        String className = getClassName(storage, c, translatedPrefix);

        writer.write("CLASS\t");
        writer.write(className);
        writer.write('\t');
        if (keepGlue) {
        	writer.write(c.getFullyQualifiedName());
        	writer.write('\t');
        }
        String serverName = server.getClass(c.getFullyQualifiedName());
        if (serverName != null) writer.write(serverName);
        writer.write('\t');
        String clientName = client.getClass(c.getFullyQualifiedName());
        if (clientName != null) writer.write(clientName);
        writer.write('\n');

        Remapper remapper = new InterRemapper(storage);
        
        for (JarFieldEntry f : c.getFields()) {
            String fName = getFieldName(storage, c, f);
            if (fName == null) {
                //fName = f.getName();
            	System.out.println("Skipped nooping " + c.getFullyQualifiedName() + '#' + f.getName());
            }

            if (fName != null) {
                writer.write("FIELD\t");
                writer.write(className);
                writer.write('\t');
                writer.write(remapper.mapDesc(f.getDescriptor()));
                writer.write('\t');
                writer.write(fName);
                writer.write('\t');
                if (keepGlue) {
	                writer.write(f.getName());
	                writer.write('\t');
                }
                EntryTriple serverField = server.getField(c.getFullyQualifiedName(), f.getName(), f.getDescriptor());
                if (serverField != null) writer.write(serverField.getName());
                writer.write('\t');
                EntryTriple clientField = client.getField(c.getFullyQualifiedName(), f.getName(), f.getDescriptor());
                if (clientField != null) writer.write(clientField.getName());
                assert serverField != null || clientField != null: "Unable to find origin for " + c.getFullyQualifiedName() + '#' + f.getName() + " (" + f.getDescriptor() + ')';
                writer.write('\n');
            }
        }

        for (JarMethodEntry m : c.getMethods()) {
            String mName = getMethodName(storageOld, storage, c, m);
            if (mName == null) {
                if (!m.getName().startsWith("<") && m.isSource(storage, c)) {
                   //mName = m.getName();
                   System.out.println("Skipped nooping " + c.getFullyQualifiedName() + '#' + m.getName() + m.getDescriptor());
                }
            }

            if (mName != null) {
                writer.write("METHOD\t");
                writer.write(className);
                writer.write('\t');
                writer.write(remapper.mapMethodDesc(m.getDescriptor()));
                writer.write('\t');
                writer.write(mName);
                writer.write('\t');
                if (keepGlue) {
	                writer.write(m.getName());
	                writer.write('\t');
                }
                EntryTriple serverMethod = server.getMethod(c.getFullyQualifiedName(), m.getName(), m.getDescriptor());
                if (serverMethod != null) writer.write(serverMethod.getName());
                writer.write('\t');
                EntryTriple clientMethod = client.getMethod(c.getFullyQualifiedName(), m.getName(), m.getDescriptor());
                if (clientMethod != null) writer.write(clientMethod.getName());
                assert serverMethod != null || clientMethod != null: "Unable to find origin for " + c.getFullyQualifiedName() + '#' + m.getName() + m.getDescriptor();
                writer.write('\n');
            }
        }

        for (JarClassEntry cc : c.getInnerClasses()) {
            addClass(writer, cc, storageOld, storage, className + '$');
        }
    }

    public void prepareRewrite(File oldMappings) throws IOException {
        oldToIntermediary = new GenMap();
        newToOld = new GenMap.Dummy();

        // TODO: only read once
        try (FileReader fileReader = new FileReader(oldMappings)) {
            try (BufferedReader reader = new BufferedReader(fileReader)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("# INTERMEDIARY-COUNTER")) {
                        String[] parts = line.split(" ");
                        counters.put(parts[2], Integer.parseInt(parts[3]));
                    }
                }
            }
        }

        try (FileInputStream inputStream = new FileInputStream(oldMappings)) {
            oldToIntermediary.load(
                    MappingsProvider.readTinyMappings(inputStream),
                    "official",
                    "intermediary"
            );
        }
    }

    public void prepareUpdate(File oldMappings, File matches) throws IOException {
        oldToIntermediary = new GenMap();
        newToOld = new GenMap();

        // TODO: only read once
        try (FileReader fileReader = new FileReader(oldMappings)) {
            try (BufferedReader reader = new BufferedReader(fileReader)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("# INTERMEDIARY-COUNTER")) {
                        String[] parts = line.split(" ");
                        counters.put(parts[2], Integer.parseInt(parts[3]));
                    }
                }
            }
        }

        try (FileInputStream inputStream = new FileInputStream(oldMappings)) {
            oldToIntermediary.load(
                    MappingsProvider.readTinyMappings(inputStream),
                    "glue",
                    "intermediary"
            );
        }

        try (FileReader fileReader = new FileReader(matches)) {
            try (BufferedReader reader = new BufferedReader(fileReader)) {
                MatcherUtil.read(reader, true, newToOld::addClass, newToOld::addField, newToOld::addMethod);
            }
        }
    }
}
