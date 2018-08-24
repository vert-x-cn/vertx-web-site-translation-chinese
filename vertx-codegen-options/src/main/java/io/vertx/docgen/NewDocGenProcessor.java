package io.vertx.docgen;

import com.sun.source.doctree.*;
import com.sun.source.util.DocTreeScanner;
import com.sun.source.util.DocTrees;
import com.sun.source.util.TreePath;
import com.sun.tools.javac.code.Symbol;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedOptions;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@SuppressWarnings("ALL")
@SupportedOptions({
        NewDocGenProcessor.DOCGEN_OUTPUT,
        NewDocGenProcessor.DOCGEN_EXTENSION,
        NewDocGenProcessor.DOCGEN_SOURCE,
        NewDocGenProcessor.DOCGEN_SYNTAX,
        "maven.artifactId", "docgen.generators", "maven.version", "maven.groupId"
})
public abstract class NewDocGenProcessor extends AbstractProcessor {

    static final String DOCGEN_OUTPUT = "docgen.output", DOCGEN_EXTENSION = "docgen.extension",
            DOCGEN_SOURCE = "docgen.source", DOCGEN_SYNTAX = "docgen.syntax";

    private DocTrees docTrees;
    protected Helper helper;
    protected List<String> sources;
    private Set<PostProcessor> postProcessors = new LinkedHashSet<>();
    private Map<String, NewDocGenProcessor.ElementResolution> resolutions = new HashMap<>();
    protected Syntax syntax;
    private Map<String, String> failures = new HashMap<>();

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.RELEASE_8;
    }

    @Override
    public Set<String> getSupportedOptions() {
        return new HashSet<>(Arrays.asList(DOCGEN_OUTPUT, DOCGEN_EXTENSION, DOCGEN_SOURCE));
    }

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        return Collections.singleton("*");
    }

    private synchronized NewDocGenProcessor registerPostProcessor(PostProcessor postProcessor) {
        if (getPostProcessor(postProcessor.getName()) != null) {
            throw new IllegalArgumentException("Post-processor with name '" + postProcessor.getName() + "' is already " +
                    "registered.");
        }
        postProcessors.add(postProcessor);
        return this;
    }

    private synchronized PostProcessor getPostProcessor(String name) {
        for (PostProcessor pp : postProcessors) {
            if (pp.getName().equalsIgnoreCase(name)) {
                return pp;
            }
        }
        return null;
    }


    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        String sourceOpt = processingEnv.getOptions().get(DOCGEN_SOURCE);
        if (sourceOpt != null) {
            sources = new ArrayList<>(Arrays.asList(sourceOpt.split("\\s*,\\s*")));
        }
        docTrees = DocTrees.instance(processingEnv);
        helper = new Helper(processingEnv);
        String syntaxOpt = processingEnv.getOptions().get(DOCGEN_SYNTAX);
        this.syntax = "markdown".equals(syntaxOpt) ? Syntax.MARKDOWN : Syntax.ASCIIDOC;
        registerPostProcessor(new LanguageFilterPostProcessor());
    }

    private String render(List<? extends DocTree> trees) {
        StringBuilder buffer = new StringBuilder();
        DocTreeVisitor<Void, Void> visitor = new DocTreeScanner<Void, Void>() {
            @Override
            public Void visitText(TextTree node, Void aVoid) {
                buffer.append(node.getBody());
                return super.visitText(node, aVoid);
            }
        };
        trees.forEach(tree -> tree.accept(visitor, null));
        return buffer.toString();
    }

    private final Map<NewDocGenProcessor.Doc, Map<DocGenerator, DocWriter>> state = new HashMap<>();

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        StackTraceElement[] trace = Thread.currentThread().getStackTrace();
        for (StackTraceElement elt : trace) {
            if (elt.getClassName().startsWith("org.jetbrains")) {
                return true;
            }
        }
        if (failures.isEmpty()) {
            try {
                if (!roundEnv.processingOver()) {
                    roundEnv.getElementsAnnotatedWith(Document.class).forEach(elt -> {
                        try {
                            NewDocGenProcessor.PackageDoc doc = new NewDocGenProcessor.PackageDoc((PackageElement) elt);
                            state.put(doc, handleGen(doc));
                        } catch (DocGenException e) {
                            if (e.element == null) {
                                e.element = elt;
                            }
                            throw e;
                        }
                    });

                    if (sources != null && sources.size() > 0) {
                        for (String source : sources) {

                            // Handle wildcards
                            List<File> files = new ArrayList<>();
                            File f = new File(source);
                            if (!f.exists()) {
                                if (f.getName().contains("*")) {
                                    StringBuilder sb = new StringBuilder();
                                    for (char c : f.getName().toCharArray()) {
                                        if (c == '*') {
                                            sb.append(".*");
                                        } else {
                                            sb.append(Matcher.quoteReplacement(Character.toString(c)));
                                        }
                                    }
                                    Pattern p = Pattern.compile(sb.toString());
                                    File parentFile = f.getParentFile();
                                    File[] children = parentFile.listFiles();
                                    if (children != null) {
                                        for (File child : children) {
                                            if (p.matcher(child.getName()).matches()) {
                                                files.add(child);
                                            }
                                        }
                                    }
                                } else {
                                    throw new FileNotFoundException("Cannot process document " + source);
                                }
                            } else {
                                files.add(f);
                            }
                            for (File file : files) {
                                if (file.isFile()) {
                                    NewDocGenProcessor.FileDoc fileDoc = new NewDocGenProcessor.FileDoc(file, file.getName());
                                    Map<DocGenerator, DocWriter> m = handleGen(fileDoc);
                                    state.put(fileDoc, m);
                                } else if (file.isDirectory()) {
                                    Files.walk(file.toPath())
                                            .map(Path::toFile)
                                            .filter(File::isFile).forEach(docFile -> {
                                        String relativePath = file.toPath().relativize(docFile.toPath()).toString();
                                        NewDocGenProcessor.FileDoc fileDoc = new NewDocGenProcessor.FileDoc(docFile, relativePath);
                                        Map<DocGenerator, DocWriter> m = handleGen(fileDoc);
                                        state.put(fileDoc, m);
                                    });
                                } else {
                                    throw new IOException("Document " + file.getAbsolutePath() + " is not a file nor a dir");
                                }
                            }
                        }
                        sources.clear();
                    }

                    Set<String> processed = new HashSet<>();
                    while (true) {
                        Optional<NewDocGenProcessor.ElementResolution> opt = resolutions
                                .values()
                                .stream()
                                .filter(res -> res.elt == null && !processed.contains(res.signature))
                                .findFirst();
                        if (opt.isPresent()) {
                            NewDocGenProcessor.ElementResolution res = opt.get();
                            processed.add(res.signature);
                            res.tryResolve();
                        } else {
                            break;
                        }
                    }
                } else {
                    state.forEach((doc, m) -> {
                        m.forEach((gen, w) -> {
                            String artifactId = null;
                            if (doc instanceof FileDoc) {
                                String n = ((FileDoc) doc).relativePath;
                                //确保asciidoc目录下，以artifactId开头，则可以找到
                                Matcher matcher = VERTX_PATTERN.matcher(n);
                                if (matcher.matches()) {
                                    artifactId = matcher.group(1);
                                }
                            }
                            String content = postProcess(gen.getName(), w.render(), artifactId);
                            write(gen, doc, content);
                        });
                    });
                }
            } catch (Exception e) {
                Element reportedElt = (e instanceof DocGenException) ? ((DocGenException) e).element : null;
                String msg = e.getMessage();
                if (msg == null) {
                    msg = e.toString();
                }
                e.printStackTrace();
                if (reportedElt != null) {
                    processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, msg, reportedElt);
                    if (reportedElt instanceof PackageElement) {
                        failures.put(((PackageElement) reportedElt).getQualifiedName().toString(), msg);
                    } else {
                        throw new UnsupportedOperationException("not implemented");
                    }
                } else {
                    processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, msg);
                }
            }
        }
        return false;
    }

    protected abstract Iterable<DocGenerator> generators();

    private Map<DocGenerator, DocWriter> handleGen(NewDocGenProcessor.Doc doc) {
        Map<DocGenerator, DocWriter> map = new HashMap<>();
        for (DocGenerator generator : generators()) {
            generator.init(processingEnv);
            DocWriter writer = new DocWriter();
            doc.process(generator, writer);
            map.put(generator, writer);
        }
        return map;
    }

    /**
     * @return the extension obtained from processor option {@literal docgen.extension} defaults to {@literal .adoc}
     * when absent.
     */
    protected String getExtension() {
        String extension = processingEnv.getOptions().get(DOCGEN_EXTENSION);
        if (extension != null) {
            return extension;
        }
        return ".adoc";
    }

    protected String resolveLinkToPackageDoc(PackageElement elt) {
        Document annotation = elt.getAnnotation(Document.class);
        String fileName = annotation.fileName();
        if (fileName.isEmpty()) {
            return elt.toString() + getExtension();
        } else {
            return fileName;
        }
    }

    /**
     * Resolve the coordinate of the type element, this method returns either:
     * <ul>
     * <li>a {@link io.vertx.docgen.Coordinate} object, the coordinate object can have null fields</li>
     * <li>{@code null} : the current element is being compiled, which likely means create a local link</li>
     * </ul>
     *
     * @param typeElt the type element to resolve
     * @return the resolved coordinate object or null if the element is locally compiled
     */
    private Coordinate resolveCoordinate(TypeElement typeElt) {
        try {
            Symbol.ClassSymbol cs = (Symbol.ClassSymbol) typeElt;
            if (cs.sourcefile != null && getURL(cs.sourcefile) != null) {
                // .java source we can link locally
                return null;
            }
            if (cs.classfile != null) {
                JavaFileObject cf = cs.classfile;
                URL classURL = getURL(cf);
                if (classURL != null && classURL.getFile().endsWith(".class")) {
                    URL manifestURL = new URL(classURL.toString().substring(0, classURL.toString().length() - (typeElt.getQualifiedName().toString().length() + 6)) + "META-INF/MANIFEST.MF");
                    InputStream manifestIs = manifestURL.openStream();
                    if (manifestIs != null) {
                        Manifest manifest = new Manifest(manifestIs);
                        Attributes attributes = manifest.getMainAttributes();
                        String groupId = attributes.getValue(new Attributes.Name("Maven-Group-Id"));
                        String artifactId = attributes.getValue(new Attributes.Name("Maven-Artifact-Id"));
                        String version = attributes.getValue(new Attributes.Name("Maven-Version"));
                        return new Coordinate(groupId, artifactId, version);
                    }
                }
            }
        } catch (Exception ignore) {
            //
        }
        return new Coordinate(null, null, null);
    }

    private URL getURL(JavaFileObject fileObject) {
        try {
            return fileObject.toUri().toURL();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Resolve a label for the specified element, this is used when a link to a program element
     * does not specify an explicit label.<p/>
     * <p/>
     * Subclasses can override it to implement a particular behavior for elements.
     *
     * @param elt the elt to resolve a label for
     * @return the label
     */
    private String resolveLabel(DocGenerator generator, Element elt) {
        String label = elt.getSimpleName().toString();
        if (elt.getModifiers().contains(Modifier.STATIC) &&
                (elt.getKind() == ElementKind.METHOD || elt.getKind() == ElementKind.FIELD)) {
            label = elt.getEnclosingElement().getSimpleName() + "." + label;
        }
        if (elt.getKind() == ElementKind.ANNOTATION_TYPE) {
            label = "@" + label;
        }
        return generator.resolveLabel(elt, label);
    }

    private final LinkedList<PackageElement> stack = new LinkedList<>();

    abstract class Doc {

        abstract String id();

        abstract String resolveRelativeFileName(DocGenerator generator);

        protected final void process(DocGenerator generator, DocWriter writer) {

            if (this instanceof NewDocGenProcessor.PackageDoc) {
                PackageElement pkgElt = ((NewDocGenProcessor.PackageDoc) this).elt;
                for (PackageElement stackElt : stack) {
                    if (pkgElt.getQualifiedName().equals(stackElt.getQualifiedName())) {
                        throw new DocGenException(stack.peekLast(), "Circular include");
                    }
                }
                stack.addLast(pkgElt);

                String pkgSource = helper.readSource(pkgElt);
                TreePath pkgPath = docTrees.getPath(pkgElt);
                DocCommentTree docTree = docTrees.getDocCommentTree(pkgPath);
                DocTreeVisitor<Void, Void> visitor = new DocTreeScanner<Void, Void>() {

                    private void copyContent(DocTree node) {
                        int from = (int) docTrees.getSourcePositions().getStartPosition(pkgPath.getCompilationUnit(), docTree, node);
                        int to = (int) docTrees.getSourcePositions().getEndPosition(pkgPath.getCompilationUnit(), docTree, node);
                        writer.append(pkgSource, from, to);
                    }

                    @Override
                    public Void visitUnknownBlockTag(UnknownBlockTagTree node, Void v) {
                        writer.append("@").append(node.getTagName()).append(" ");
                        return super.visitUnknownBlockTag(node, v);
                    }

                    @Override
                    public Void visitDocComment(DocCommentTree node, Void v) {
                        v = scan(node.getFirstSentence(), v);
                        List<? extends DocTree> body = node.getBody();
                        if (body.size() > 0) {
                            writer.append("\n\n");
                            writer.resetParagraph();
                            v = scan(body, v);
                        }
                        List<? extends DocTree> blockTags = node.getBlockTags();
                        if (blockTags.size() > 0) {
                            writer.append("\n");
                            v = scan(blockTags, v);
                        }
                        return v;
                    }

                    @Override
                    public Void visitErroneous(ErroneousTree node, Void v) {
                        return visitText(node, v);
                    }

                    @Override
                    public Void visitText(TextTree node, Void v) {
                        String body = node.getBody();
                        helper.filterLang(body, generator.getName(), writer);
                        return super.visitText(node, v);
                    }

                    /**
                     * Handles both literal and code. We generate the asciidoc output using {@literal `}.
                     */
                    @Override
                    public Void visitLiteral(LiteralTree node, Void aVoid) {
                        writer.append("`").append(node.getBody().getBody()).append("`");
                        return super.visitLiteral(node, aVoid);
                    }

                    @Override
                    public Void visitEntity(EntityTree node, Void aVoid) {
                        writer.append(EntityUtils.unescapeEntity(node.getName().toString()));
                        return super.visitEntity(node, aVoid);
                    }

                    @Override
                    public Void visitStartElement(StartElementTree node, Void v) {
                        copyContent(node);
                        return v;
                    }

                    @Override
                    public Void visitEndElement(EndElementTree node, Void v) {
                        writer.write("</");
                        writer.append(node.getName());
                        writer.append('>');
                        return v;
                    }

                    @Override
                    public Void visitLink(LinkTree node, Void v) {
                        String signature = node.getReference().getSignature();
                        String label = render(node.getLabel()).trim();
                        NewDocGenProcessor.this.visitLink(pkgElt, label, signature, generator, writer);
                        return v;
                    }
                };
                docTree.accept(visitor, null);
                stack.removeLast();
            } else {
                NewDocGenProcessor.FileDoc fileDoc = (NewDocGenProcessor.FileDoc) this;
                try {
                    String content = new String(Files.readAllBytes(fileDoc.file.toPath()), StandardCharsets.UTF_8);
                    StringBuilder intermediate = new StringBuilder(content.length());
                    helper.filterLang(content, generator.getName(), intermediate);
                    content = intermediate.toString();
                    Matcher linkMatcher = LINK_PATTERN.matcher(content);
                    int prev = 0;
                    while (linkMatcher.find()) {
                        writer.write(content, prev, linkMatcher.start() - prev);
                        String value = linkMatcher.group(1).trim();
                        Matcher methodLinkMatcher = METHOD_LINK_PATTERN.matcher(value);
                        if (methodLinkMatcher.find()) {
                            String signature = value.substring(0, methodLinkMatcher.end());
                            String label = value.substring(methodLinkMatcher.end()).trim();
                            writer.exec(() -> {
                                NewDocGenProcessor.this.visitLink(null, label, signature, generator, writer);
                            });
                        }
                        prev = linkMatcher.end();
                    }
                    writer.append(content, prev, content.length());
                } catch (IOException e) {
                    throw new DocGenException(e.getMessage());
                }
            }
        }
    }

    class PackageDoc extends NewDocGenProcessor.Doc {

        final PackageElement elt;

        PackageDoc(PackageElement elt) {
            this.elt = elt;
        }

        @Override
        public String id() {
            return elt.getQualifiedName().toString();
        }

        /**
         * Return the relative file name of a document.
         *
         * @param generator the doc generator
         * @return the relative file name
         */
        public String resolveRelativeFileName(DocGenerator generator) {
            Document doc = elt.getAnnotation(Document.class);
            String relativeName = doc.fileName();
            if (relativeName.isEmpty()) {
                relativeName = elt.getQualifiedName() + getExtension();
            }
            return generator.resolveRelativeFileName(elt, relativeName);
        }
    }

    class FileDoc extends NewDocGenProcessor.Doc {

        final File file;
        final String relativePath;

        FileDoc(File file, String relativePath) {
            this.file = file;
            this.relativePath = relativePath;
        }

        @Override
        public String id() {
            return relativePath;
        }

        @Override
        public String resolveRelativeFileName(DocGenerator generator) {
            return relativePath;
        }
    }

    private static final Pattern VERTX_PATTERN = Pattern.compile("(vertx-[^\\\\]*).*");
    private static final Pattern LINK_PATTERN = Pattern.compile("\\{@link\\s([^}]+)\\}");
    private static final Pattern METHOD_LINK_PATTERN = Pattern.compile(
            "^([$_\\w]+\\.)*[$_\\w]+" +
                    "(?:" +
                    "#[$_\\w]+" +
                    "(?:(?:\\([^)]*)\\)|$|(?= ))" +
                    ")?");

    private void visitLink(PackageElement pkgElt, String label, String signature, DocGenerator generator, DocWriter writer) {
        NewDocGenProcessor.ElementResolution res = resolutions.get(signature);
        if (res == null) {
            res = new NewDocGenProcessor.ElementResolution(signature);
            resolutions.put(signature, res);
        }
        NewDocGenProcessor.LinkProcessing fut = new NewDocGenProcessor.LinkProcessing(generator, label);
        res.add(fut);
        writer.write(() -> {
            DocWriter ww = fut.writer;
            if (ww == null) {
                throw new DocGenException(pkgElt, "Could not resolve " + signature);
            }
            return ww;
        });
    }

    /**
     * The resolution of an element.
     */
    class ElementResolution {

        final String signature;
        private Element elt;
        private List<NewDocGenProcessor.LinkProcessing> handlers = new ArrayList<>();

        public ElementResolution(String signature) {
            this.signature = signature;
        }

        boolean tryResolve() {
            if (elt == null) {
                doResolve();
            }
            return elt != null;
        }

        public boolean equals(Object o) {
            if (o instanceof NewDocGenProcessor.ElementResolution) {
                NewDocGenProcessor.ElementResolution that = (NewDocGenProcessor.ElementResolution) o;
                return signature.equals(that.signature);
            } else {
                return false;
            }
        }

        @Override
        public int hashCode() {
            return signature.hashCode();
        }

        private void doResolve() {
            elt = helper.resolveLink(signature);
            if (elt != null) {
                for (NewDocGenProcessor.LinkProcessing fut : handlers) {
                    fut.handle(elt);
                }
                handlers.clear();
            }
        }

        private void add(NewDocGenProcessor.LinkProcessing fut) {
            if (elt != null) {
                fut.handle(elt);
            } else {
                handlers.add(fut);
            }
        }
    }

    class LinkProcessing {

        final DocGenerator generator;
        final String label;
        private DocWriter writer;

        public LinkProcessing(DocGenerator generator, String label) {
            this.generator = generator;
            this.label = label;
        }

        void handle(Element elt) {
            writer = new DocWriter();
            if (elt instanceof PackageElement) {
                PackageElement includedElt = (PackageElement) elt;
                if (includedElt.getAnnotation(Document.class) == null) {
                    new NewDocGenProcessor.PackageDoc(includedElt).process(generator, writer);
                } else {
                    String link = resolveLinkToPackageDoc((PackageElement) elt);
                    writer.append(link);
                }
            } else {
                if (helper.isExample(elt)) {
                    String source = helper.readSource(elt);
                    switch (elt.getKind()) {
                        case CONSTRUCTOR:
                        case METHOD:
                            // Check whether or not the fragment must be translated
                            String fragment;
                            if (helper.hasToBeTranslated(elt)) {
                                // Invoke the custom renderer, this may should the translation to the expected language.
                                fragment = generator.renderSource((ExecutableElement) elt, source);
                            } else {
                                // Do not call the custom rendering process, just use the default / java one.
                                JavaDocGenerator javaGen = new JavaDocGenerator();
                                javaGen.init(processingEnv);
                                fragment = javaGen.renderSource((ExecutableElement) elt, source);
                            }
                            if (fragment != null) {
                                writer.literalMode();
                                writer.append(fragment);
                                writer.commentMode();
                            }
                            return;
                        case CLASS:
                        case INTERFACE:
                        case ENUM:
                        case ANNOTATION_TYPE:
                            TypeElement typeElt = (TypeElement) elt;
                            JavaDocGenerator javaGen = new JavaDocGenerator();
                            javaGen.init(processingEnv);
                            fragment = javaGen.renderSource(typeElt, source);
                            if (fragment != null) {
                                writer.literalMode();
                                writer.append(fragment);
                                writer.commentMode();
                            }
                            return;
                        default:
                            throw new UnsupportedOperationException("unsupported element: " + elt.getKind());
                    }
                }
                String link;
                switch (elt.getKind()) {
                    case CLASS:
                    case INTERFACE:
                    case ANNOTATION_TYPE:
                    case ENUM: {
                        TypeElement typeElt = (TypeElement) elt;
                        link = generator.resolveTypeLink(typeElt, resolveCoordinate(typeElt));
                        break;
                    }
                    case METHOD: {
                        ExecutableElement methodElt = (ExecutableElement) elt;
                        TypeElement typeElt = (TypeElement) methodElt.getEnclosingElement();
                        link = generator.resolveMethodLink(methodElt, resolveCoordinate(typeElt));
                        break;
                    }
                    case CONSTRUCTOR: {
                        ExecutableElement constructorElt = (ExecutableElement) elt;
                        TypeElement typeElt = (TypeElement) constructorElt.getEnclosingElement();
                        link = generator.resolveConstructorLink(constructorElt, resolveCoordinate(typeElt));
                        break;
                    }
                    case FIELD:
                    case ENUM_CONSTANT: {
                        VariableElement variableElt = (VariableElement) elt;
                        TypeElement typeElt = (TypeElement) variableElt.getEnclosingElement();
                        link = generator.resolveFieldLink(variableElt, resolveCoordinate(typeElt));
                        break;
                    }
                    default:
                        throw new UnsupportedOperationException("Not yet implemented " + elt + " with kind " + elt.getKind());
                }
                String s;
                if (label.length() == 0) {
                    s = resolveLabel(generator, elt);
                } else {
                    s = label;
                }
                if (link != null) {
                    syntax.writeLink(link, s, writer);
                } else {
                    writer.append("`").append(s).append("`");
                }
            }
        }
    }

    protected String postProcess(String name, String content, String artifactId) {
        String processed = applyVariableSubstitution(content, artifactId);
        processed = applyPostProcessors(name, processed);
        return processed;
    }

    protected void write(DocGenerator generator, NewDocGenProcessor.Doc doc, String content) {
        String outputOpt = processingEnv.getOptions().get(DOCGEN_OUTPUT);
        if (outputOpt != null) {
            outputOpt = outputOpt.replace("$lang", generator.getName());
            String relativeName = doc.resolveRelativeFileName(generator);
            try {
                File dir = new File(outputOpt);
                for (int i = relativeName.indexOf('/'); i != -1; i = relativeName.indexOf('/', i + 1)) {
                    dir = new File(dir, relativeName.substring(0, i));
                    relativeName = relativeName.substring(i + 1);
                }
                File file = new File(dir, relativeName);
                ensureDir(file.getParentFile());
                try (FileWriter writer = new FileWriter(file)) {
                    writer.write(content);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Apply post-processors.
     *
     * @param content the (asciidoc) content
     * @return the content after post-processing.
     */
    protected String applyPostProcessors(String name2, String content) {
        final List<String> lines = Arrays.asList(content.split("\r?\n"));
        StringBuilder processed = new StringBuilder();
        Iterator<String> iterator = lines.iterator();
        while (iterator.hasNext()) {
            String line = iterator.next();
            String trimmedLine = line.trim();
            if (!PostProcessor.isBlockDeclaration(trimmedLine)) {
                processed.append(line);
                if (iterator.hasNext()) {
                    processed.append("\n");
                }
            } else {
                String name = PostProcessor.getProcessorName(trimmedLine);
                String[] attributes = PostProcessor.getProcessorAttributes(trimmedLine);
                PostProcessor postProcessor = getPostProcessor(name);
                if (postProcessor == null) {
                    processed.append(line);
                    if (iterator.hasNext()) {
                        processed.append("\n");
                    }
                } else {
                    // Extract content.
                    String block = PostProcessor.getBlockContent(iterator);
                    processed.append(postProcessor.process(name2, block, attributes));
                    if (iterator.hasNext()) {
                        processed.append("\n");
                    }
                }
            }
        }
        return processed.toString();
    }

    private void ensureDir(File dir) {
        if (dir.exists()) {
            if (!dir.isDirectory()) {
                throw new DocGenException("File " + dir.getAbsolutePath() + " is not a dir");
            }
        } else if (!dir.mkdirs()) {
            throw new DocGenException("could not create dir " + dir.getAbsolutePath());
        }
    }

    /**
     * Replace `@{var} by the variable value passed to the annotation processor.
     *
     * @param content the content
     * @return the content with variable values
     */
    public String applyVariableSubstitution(String content, String artifactId) {
        for (Map.Entry<String, String> entry : processingEnv.getOptions().entrySet()) {
            content = content.replace("${" + entry.getKey() + "}", entry.getValue());
        }
        if (artifactId != null) {
            content = content.replace("${maven.artifactId}", artifactId);
        }
        return content;
    }
}
