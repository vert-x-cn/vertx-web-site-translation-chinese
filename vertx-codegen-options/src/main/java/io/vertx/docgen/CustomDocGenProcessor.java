package io.vertx.docgen;

import javax.annotation.processing.ProcessingEnvironment;
import java.util.*;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class CustomDocGenProcessor extends DocGenProcessor {
    private List<DocGenerator> generators;

    public CustomDocGenProcessor() {
    }

    public CustomDocGenProcessor(DocGenerator... generators) {
        this.generators = Arrays.asList(generators.clone());
    }

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        // Service loader generators
        if (generators == null) {
            generators = new ArrayList<>();
            Iterator<DocGenerator> it = ServiceLoader.load(DocGenerator.class, DocGenProcessor.class.getClassLoader()).iterator();
            while (it.hasNext()) {
                try {
                    generators.add(it.next());
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }

    @Override
    protected Iterable<DocGenerator> generators() {
        Predicate<DocGenerator> filter = filterDocGenerators();
        if (filter != null) {
            return generators.stream().filter(filter).collect(Collectors.toList());
        } else {
            return generators;
        }
    }

    protected Predicate<DocGenerator> filterDocGenerators() {
        String generatorsOptions = processingEnv.getOptions().get("docgen.generators");
        if (generatorsOptions != null) {
            List<Pattern> wanted = Stream.of(generatorsOptions.split(","))
                    .map(String::trim)
                    .map(Pattern::compile)
                    .collect(Collectors.toList());
            return dg -> wanted.stream().anyMatch(p -> p.matcher(dg.getName()).matches());
        } else {
            return null;
        }
    }
}
