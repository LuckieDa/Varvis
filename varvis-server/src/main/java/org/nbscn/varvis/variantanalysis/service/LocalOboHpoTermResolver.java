package org.nbscn.varvis.variantanalysis.service;

import org.nbscn.varvis.variantanalysis.domain.HpoOntologyTermStatus;

import java.io.Closeable;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.FileSystems;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributeView;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * Immutable, fail-closed HPO resolver backed by one explicitly configured local OBO file.
 * The configured file is read once; no discovery, reload, classpath lookup, URL or network access occurs.
 */
public final class LocalOboHpoTermResolver implements HpoTermResolver {

    static final long MAX_BYTES = 64L * 1024L * 1024L;
    static final int MAX_LINE_LENGTH = 16 * 1024;
    static final int MAX_TERMS = 100_000;
    static final int MAX_ALIASES = 100_000;
    static final int MAX_REPLACEMENT_EDGES = 500_000;
    private static final Pattern HPO_ID = Pattern.compile("^HP:\\d{7}$");
    private static final Pattern SHA256 = Pattern.compile("^[0-9a-fA-F]{64}$");

    private final AtomicReference<Snapshot> snapshot = new AtomicReference<Snapshot>(Snapshot.unavailable());

    public LocalOboHpoTermResolver(Path configuredFile,
                                   String expectedDataVersion,
                                   String expectedSha256) {
        Snapshot parsed = load(configuredFile, expectedDataVersion, expectedSha256);
        snapshot.set(parsed);
    }

    @Override
    public Resolution resolve(String hpoId) {
        String requested = hpoId == null ? null : hpoId.trim().toUpperCase(Locale.ROOT);
        Snapshot current = snapshot.get();
        if (!current.available) {
            return new Resolution(
                    requested,
                    requested,
                    null,
                    null,
                    HpoOntologyTermStatus.UNAVAILABLE,
                    true,
                    "HPO_ONTOLOGY_UNAVAILABLE"
            );
        }
        String primaryId = current.aliases.get(requested);
        boolean alias = primaryId != null;
        String resolvedId = alias ? primaryId : requested;
        Term term = current.terms.get(resolvedId);
        if (term == null) {
            return new Resolution(
                    requested,
                    null,
                    null,
                    null,
                    HpoOntologyTermStatus.UNKNOWN,
                    false,
                    "HPO_TERM_UNKNOWN"
            );
        }
        if (!term.obsolete) {
            return new Resolution(
                    requested,
                    resolvedId,
                    alias ? resolvedId : null,
                    term.name,
                    alias ? HpoOntologyTermStatus.CURRENT_ALIAS : HpoOntologyTermStatus.CURRENT,
                    true,
                    alias ? "HPO_ALIAS_NORMALIZED" : null
            );
        }
        if (term.replacedBy.isEmpty()) {
            return new Resolution(
                    requested,
                    null,
                    null,
                    term.name,
                    HpoOntologyTermStatus.OBSOLETE_UNRESOLVED,
                    false,
                    "HPO_OBSOLETE_UNRESOLVED"
            );
        }
        if (term.replacedBy.size() != 1) {
            return new Resolution(
                    requested,
                    null,
                    null,
                    term.name,
                    HpoOntologyTermStatus.OBSOLETE_AMBIGUOUS,
                    false,
                    "HPO_OBSOLETE_AMBIGUOUS"
            );
        }
        ReplacementOutcome outcome = current.replacementOutcomes.get(resolvedId);
        if (outcome == null || outcome.effectiveHpoId == null) {
            HpoOntologyTermStatus unresolvedStatus = outcome == null
                    ? HpoOntologyTermStatus.OBSOLETE_UNRESOLVED
                    : outcome.status;
            return new Resolution(
                    requested,
                    null,
                    null,
                    term.name,
                    unresolvedStatus,
                    false,
                    unresolvedStatus == HpoOntologyTermStatus.OBSOLETE_AMBIGUOUS
                            ? "HPO_OBSOLETE_AMBIGUOUS"
                            : "HPO_OBSOLETE_REPLACEMENT_NOT_CURRENT"
            );
        }
        String replacement = outcome.effectiveHpoId;
        Term replacementTerm = current.terms.get(replacement);
        return new Resolution(
                requested,
                replacement,
                replacement,
                replacementTerm == null ? null : replacementTerm.name,
                HpoOntologyTermStatus.OBSOLETE_WITH_REPLACEMENT,
                true,
                "HPO_OBSOLETE_REPLACED"
        );
    }

    @Override
    public boolean isAvailable() {
        return snapshot.get().available;
    }

    @Override
    public String getDataVersion() {
        return snapshot.get().dataVersion;
    }

    private Snapshot load(Path configuredFile, String expectedDataVersion, String expectedSha256) {
        String expectedVersion = trimToNull(expectedDataVersion);
        String expectedHash = trimToNull(expectedSha256);
        if (configuredFile == null
                || expectedVersion == null
                || expectedHash == null
                || !SHA256.matcher(expectedHash).matches()) {
            return Snapshot.unavailable();
        }
        try {
            Path file = validateExactFile(configuredFile);
            BasicFileAttributes before = Files.readAttributes(
                    file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (before.size() > MAX_BYTES) {
                return Snapshot.unavailable();
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            ParsedOntology parsed;
            try (InputStream source = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS);
                 LimitedInputStream limited = new LimitedInputStream(source, MAX_BYTES);
                 DigestInputStream digested = new DigestInputStream(limited, digest);
                 BoundedLineReader reader = new BoundedLineReader(new InputStreamReader(
                         digested,
                         StandardCharsets.UTF_8.newDecoder()
                                 .onMalformedInput(CodingErrorAction.REPORT)
                                 .onUnmappableCharacter(CodingErrorAction.REPORT)
                 ), MAX_LINE_LENGTH)) {
                parsed = parse(reader);
            }
            BasicFileAttributes after = Files.readAttributes(
                    file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!sameFileSnapshot(before, after)) {
                return Snapshot.unavailable();
            }
            String actualHash = toHex(digest.digest());
            if (!actualHash.equalsIgnoreCase(expectedHash)
                    || !expectedVersion.equals(parsed.dataVersion)) {
                return Snapshot.unavailable();
            }
            Map<String, ReplacementOutcome> replacements = validateReplacementGraph(
                    parsed.terms, parsed.aliases);
            return Snapshot.available(parsed.dataVersion, parsed.terms, parsed.aliases, replacements);
        } catch (IOException | RuntimeException | NoSuchAlgorithmException ex) {
            return Snapshot.unavailable();
        }
    }

    private Path validateExactFile(Path configuredFile) throws IOException {
        if (configuredFile.getFileSystem().provider() != FileSystems.getDefault().provider()
                || !"file".equalsIgnoreCase(configuredFile.getFileSystem().provider().getScheme())) {
            throw new IOException("Only the default local file provider is allowed for the HPO ontology");
        }
        String configuredText = configuredFile.toString();
        if (configuredText.startsWith("\\\\")
                || configuredText.startsWith("//")
                || configuredFile.toUri().getAuthority() != null) {
            throw new IOException("Network and device paths are not allowed for the HPO ontology");
        }
        if (!configuredFile.isAbsolute()
                || !configuredFile.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".obo")) {
            throw new IOException("Configured HPO ontology must be one absolute .obo file");
        }
        Path normalized = configuredFile.normalize();
        Path current = normalized.getRoot();
        if (current == null) {
            throw new IOException("Configured HPO ontology has no filesystem root");
        }
        for (Path part : normalized) {
            current = current.resolve(part);
            BasicFileAttributes attributes = Files.readAttributes(
                    current,
                    BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS
            );
            if (attributes.isSymbolicLink() || attributes.isOther() || isReparsePoint(current)) {
                throw new IOException("Configured HPO ontology path contains an indirect filesystem entry");
            }
        }
        BasicFileAttributes attributes = Files.readAttributes(
                normalized,
                BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS
        );
        if (!attributes.isRegularFile() || attributes.isOther()) {
            throw new IOException("Configured HPO ontology is not a regular file");
        }
        return normalized;
    }

    private boolean isReparsePoint(Path path) throws IOException {
        if (Files.getFileAttributeView(
                path, DosFileAttributeView.class, LinkOption.NOFOLLOW_LINKS) == null) {
            return false;
        }
        Object attributes = Files.getAttribute(path, "dos:attributes", LinkOption.NOFOLLOW_LINKS);
        if (!(attributes instanceof Number)) {
            throw new IOException("DOS attributes did not return a numeric bit mask");
        }
        return ((((Number) attributes).intValue()) & 0x400) != 0;
    }

    private boolean sameFileSnapshot(BasicFileAttributes before, BasicFileAttributes after) {
        return before.size() == after.size()
                && Objects.equals(before.lastModifiedTime(), after.lastModifiedTime())
                && Objects.equals(before.fileKey(), after.fileKey())
                && after.isRegularFile()
                && !after.isOther()
                && !after.isSymbolicLink();
    }

    private ParsedOntology parse(BoundedLineReader reader) throws IOException {
        String formatVersion = null;
        String dataVersion = null;
        Map<String, Term> terms = new LinkedHashMap<String, Term>();
        Map<String, String> aliases = new LinkedHashMap<String, String>();
        TermBuilder term = null;
        int replacementEdges = 0;
        String line;
        while ((line = reader.readLine()) != null) {
            String trimmed = line.trim();
            if ("[Term]".equals(trimmed)) {
                replacementEdges += publishTerm(term, terms, aliases);
                term = new TermBuilder();
                continue;
            }
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                replacementEdges += publishTerm(term, terms, aliases);
                term = null;
                continue;
            }
            if (term == null) {
                if (trimmed.startsWith("format-version:")) {
                    formatVersion = valueAfterColon(trimmed);
                } else if (trimmed.startsWith("data-version:")) {
                    dataVersion = valueAfterColon(trimmed);
                }
                continue;
            }
            if (trimmed.startsWith("id:")) {
                term.setId(valueAfterColon(trimmed));
            } else if (trimmed.startsWith("alt_id:")) {
                term.addAlias(firstToken(valueAfterColon(trimmed)));
            } else if (trimmed.startsWith("name:")) {
                term.setName(valueAfterColon(trimmed));
            } else if (trimmed.startsWith("is_obsolete:")) {
                term.setObsolete(valueAfterColon(trimmed));
            } else if (trimmed.startsWith("replaced_by:")) {
                term.addReplacement(firstToken(valueAfterColon(trimmed)));
            }
        }
        replacementEdges += publishTerm(term, terms, aliases);
        if (!"1.2".equals(formatVersion) || dataVersion == null || terms.isEmpty()) {
            throw new IOException("OBO header or term index is incomplete");
        }
        if (terms.size() > MAX_TERMS
                || aliases.size() > MAX_ALIASES
                || replacementEdges > MAX_REPLACEMENT_EDGES) {
            throw new IOException("OBO ontology exceeds safety limits");
        }
        for (String alias : aliases.keySet()) {
            if (terms.containsKey(alias)) {
                throw new IOException("OBO alternate identifier conflicts with a primary identifier");
            }
        }
        return new ParsedOntology(dataVersion, terms, aliases);
    }

    private int publishTerm(TermBuilder builder,
                            Map<String, Term> terms,
                            Map<String, String> aliases) throws IOException {
        if (builder == null) {
            return 0;
        }
        Term term = builder.build();
        if (terms.put(term.id, term) != null) {
            throw new IOException("Duplicate OBO term identifier");
        }
        if (terms.size() > MAX_TERMS) {
            throw new IOException("OBO ontology exceeds term safety limit");
        }
        for (String alias : term.altIds) {
            if (aliases.put(alias, term.id) != null) {
                throw new IOException("Duplicate OBO alternate identifier");
            }
            if (aliases.size() > MAX_ALIASES) {
                throw new IOException("OBO ontology exceeds alternate identifier safety limit");
            }
        }
        return term.replacedBy.size();
    }

    private Map<String, ReplacementOutcome> validateReplacementGraph(Map<String, Term> terms,
                                                                      Map<String, String> aliases)
            throws IOException {
        for (Term term : terms.values()) {
            for (String replacement : term.replacedBy) {
                if (canonicalPrimary(replacement, terms, aliases) == null) {
                    throw new IOException("OBO replacement target is absent");
                }
            }
        }

        Map<String, Integer> colors = new HashMap<String, Integer>();
        List<String> postOrder = new ArrayList<String>(terms.size());
        for (String id : terms.keySet()) {
            if (colors.getOrDefault(id, 0) != 0) {
                continue;
            }
            Deque<GraphFrame> stack = new ArrayDeque<GraphFrame>();
            colors.put(id, 1);
            stack.push(new GraphFrame(id));
            while (!stack.isEmpty()) {
                GraphFrame frame = stack.peek();
                Term node = terms.get(frame.id);
                if (frame.nextChildIndex < node.replacedBy.size()) {
                    String child = canonicalPrimary(
                            node.replacedBy.get(frame.nextChildIndex++), terms, aliases);
                    if (child == null) {
                        throw new IOException("OBO replacement target is absent");
                    }
                    int childColor = colors.getOrDefault(child, 0);
                    if (childColor == 1) {
                        throw new IOException("OBO replacement cycle detected");
                    }
                    if (childColor == 0) {
                        colors.put(child, 1);
                        stack.push(new GraphFrame(child));
                    }
                    continue;
                }
                stack.pop();
                colors.put(frame.id, 2);
                postOrder.add(frame.id);
            }
        }

        Map<String, ReplacementOutcome> outcomes = new HashMap<String, ReplacementOutcome>();
        for (String id : postOrder) {
            Term term = terms.get(id);
            if (!term.obsolete) {
                outcomes.put(id, new ReplacementOutcome(HpoOntologyTermStatus.CURRENT, id));
            } else if (term.replacedBy.isEmpty()) {
                outcomes.put(id, new ReplacementOutcome(
                        HpoOntologyTermStatus.OBSOLETE_UNRESOLVED, null));
            } else if (term.replacedBy.size() > 1) {
                outcomes.put(id, new ReplacementOutcome(
                        HpoOntologyTermStatus.OBSOLETE_AMBIGUOUS, null));
            } else {
                String childId = canonicalPrimary(term.replacedBy.get(0), terms, aliases);
                ReplacementOutcome child = outcomes.get(childId);
                if (child != null && child.effectiveHpoId != null) {
                    outcomes.put(id, new ReplacementOutcome(
                            HpoOntologyTermStatus.OBSOLETE_WITH_REPLACEMENT,
                            child.effectiveHpoId
                    ));
                } else {
                    outcomes.put(id, new ReplacementOutcome(
                            child == null ? HpoOntologyTermStatus.OBSOLETE_UNRESOLVED : child.status,
                            null
                    ));
                }
            }
        }
        return Collections.unmodifiableMap(outcomes);
    }

    private String canonicalPrimary(String hpoId,
                                    Map<String, Term> terms,
                                    Map<String, String> aliases) {
        return terms.containsKey(hpoId) ? hpoId : aliases.get(hpoId);
    }

    private String valueAfterColon(String line) throws IOException {
        int separator = line.indexOf(':');
        String value = separator < 0 ? null : trimToNull(line.substring(separator + 1));
        if (value == null) {
            throw new IOException("OBO field is empty");
        }
        return value;
    }

    private String firstToken(String value) {
        int separator = value.indexOf(' ');
        return separator < 0 ? value : value.substring(0, separator);
    }

    private static String trimToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private String toHex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format("%02x", value & 0xff));
        }
        return result.toString();
    }

    private static final class ParsedOntology {
        private final String dataVersion;
        private final Map<String, Term> terms;
        private final Map<String, String> aliases;

        private ParsedOntology(String dataVersion,
                               Map<String, Term> terms,
                               Map<String, String> aliases) {
            this.dataVersion = dataVersion;
            this.terms = terms;
            this.aliases = aliases;
        }
    }

    private static final class TermBuilder {
        private String id;
        private String name;
        private boolean obsolete;
        private boolean obsoleteSeen;
        private final List<String> altIds = new ArrayList<String>();
        private final List<String> replacedBy = new ArrayList<String>();

        private void setId(String value) throws IOException {
            if (id != null || !HPO_ID.matcher(value).matches()) {
                throw new IOException("Invalid OBO term identifier");
            }
            id = value;
        }

        private void setName(String value) throws IOException {
            if (name != null) {
                throw new IOException("Duplicate OBO term name");
            }
            name = value;
        }

        private void addAlias(String value) throws IOException {
            if (!HPO_ID.matcher(value).matches() || altIds.size() >= MAX_ALIASES) {
                throw new IOException("Invalid or excessive OBO alternate identifier");
            }
            altIds.add(value);
        }

        private void setObsolete(String value) throws IOException {
            if (obsoleteSeen || !("true".equals(value) || "false".equals(value))) {
                throw new IOException("Invalid OBO obsolete flag");
            }
            obsoleteSeen = true;
            obsolete = Boolean.parseBoolean(value);
        }

        private void addReplacement(String value) throws IOException {
            if (!HPO_ID.matcher(value).matches()) {
                throw new IOException("Invalid OBO replacement identifier");
            }
            replacedBy.add(value);
        }

        private Term build() throws IOException {
            if (id == null || name == null || (!obsolete && !replacedBy.isEmpty())) {
                throw new IOException("Incomplete OBO term");
            }
            return new Term(id, name, obsolete, altIds, replacedBy);
        }
    }

    private static final class Term {
        private final String id;
        private final String name;
        private final boolean obsolete;
        private final List<String> altIds;
        private final List<String> replacedBy;

        private Term(String id,
                     String name,
                     boolean obsolete,
                     List<String> altIds,
                     List<String> replacedBy) {
            this.id = id;
            this.name = name;
            this.obsolete = obsolete;
            this.altIds = Collections.unmodifiableList(new ArrayList<String>(altIds));
            this.replacedBy = Collections.unmodifiableList(new ArrayList<String>(replacedBy));
        }
    }

    private static final class Snapshot {
        private final boolean available;
        private final String dataVersion;
        private final Map<String, Term> terms;
        private final Map<String, String> aliases;
        private final Map<String, ReplacementOutcome> replacementOutcomes;

        private Snapshot(boolean available,
                         String dataVersion,
                         Map<String, Term> terms,
                         Map<String, String> aliases,
                         Map<String, ReplacementOutcome> replacementOutcomes) {
            this.available = available;
            this.dataVersion = dataVersion;
            this.terms = terms;
            this.aliases = aliases;
            this.replacementOutcomes = replacementOutcomes;
        }

        private static Snapshot unavailable() {
            return new Snapshot(
                    false,
                    "unavailable",
                    Collections.<String, Term>emptyMap(),
                    Collections.<String, String>emptyMap(),
                    Collections.<String, ReplacementOutcome>emptyMap()
            );
        }

        private static Snapshot available(String dataVersion,
                                          Map<String, Term> terms,
                                          Map<String, String> aliases,
                                          Map<String, ReplacementOutcome> replacementOutcomes) {
            return new Snapshot(
                    true,
                    dataVersion,
                    Collections.unmodifiableMap(new LinkedHashMap<String, Term>(terms)),
                    Collections.unmodifiableMap(new LinkedHashMap<String, String>(aliases)),
                    replacementOutcomes
            );
        }
    }

    private static final class GraphFrame {
        private final String id;
        private int nextChildIndex;

        private GraphFrame(String id) {
            this.id = id;
        }
    }

    private static final class ReplacementOutcome {
        private final HpoOntologyTermStatus status;
        private final String effectiveHpoId;

        private ReplacementOutcome(HpoOntologyTermStatus status, String effectiveHpoId) {
            this.status = status;
            this.effectiveHpoId = effectiveHpoId;
        }
    }

    /** Reads a line without ever allocating more than the configured character bound. */
    private static final class BoundedLineReader implements Closeable {
        private final InputStreamReader reader;
        private final int maximumLineLength;

        private BoundedLineReader(InputStreamReader reader, int maximumLineLength) {
            this.reader = reader;
            this.maximumLineLength = maximumLineLength;
        }

        private String readLine() throws IOException {
            StringBuilder value = new StringBuilder(Math.min(256, maximumLineLength));
            boolean observed = false;
            int current;
            while ((current = reader.read()) >= 0) {
                observed = true;
                if (current == '\n') {
                    break;
                }
                if (current == '\r') {
                    continue;
                }
                if (value.length() >= maximumLineLength) {
                    throw new IOException("OBO line exceeds safety limit");
                }
                value.append((char) current);
            }
            return !observed && value.length() == 0 ? null : value.toString();
        }

        @Override
        public void close() throws IOException {
            reader.close();
        }
    }

    private static final class LimitedInputStream extends FilterInputStream {
        private final long maximum;
        private long count;

        private LimitedInputStream(InputStream input, long maximum) {
            super(input);
            this.maximum = maximum;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) {
                increment(1);
            }
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            int read = super.read(bytes, offset, length);
            if (read > 0) {
                increment(read);
            }
            return read;
        }

        private void increment(int value) throws IOException {
            count += value;
            if (count > maximum) {
                throw new IOException("OBO ontology exceeds byte safety limit");
            }
        }
    }
}
