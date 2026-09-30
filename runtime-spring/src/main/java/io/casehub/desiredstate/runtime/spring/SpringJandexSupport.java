package io.casehub.desiredstate.runtime.spring;

import io.casehub.desiredstate.api.NodeSpec;
import io.casehub.desiredstate.api.NodeTypeId;
import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.CompositeIndex;
import org.jboss.jandex.DotName;
import org.jboss.jandex.Index;
import org.jboss.jandex.IndexReader;
import org.jboss.jandex.IndexView;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class SpringJandexSupport {

    private static final DotName NODE_SPEC = DotName.createSimple(NodeSpec.class.getName());
    private static final DotName NODE_TYPE_ID = DotName.createSimple(NodeTypeId.class.getName());

    private SpringJandexSupport() {}

    public static IndexView loadCompositeIndex() {
        List<IndexView> indexes = new ArrayList<>();
        try {
            Enumeration<URL> resources = Thread.currentThread()
                    .getContextClassLoader()
                    .getResources("META-INF/jandex.idx");
            while (resources.hasMoreElements()) {
                try (InputStream is = resources.nextElement().openStream()) {
                    indexes.add(new IndexReader(is).read());
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load Jandex indexes", e);
        }
        if (indexes.isEmpty()) {
            try {
                return Index.of(new Class<?>[0]);
            } catch (IOException ex) {
                throw new IllegalStateException("Failed to create empty Jandex index", ex);
            }
        }
        return CompositeIndex.create(indexes);
    }

    public static Map<String, String> scanNodeTypes(IndexView index) {
        Map<String, String> registry = new HashMap<>();
        for (AnnotationInstance ann : index.getAnnotations(NODE_TYPE_ID)) {
            if (ann.target().kind() == org.jboss.jandex.AnnotationTarget.Kind.CLASS) {
                ClassInfo cls = ann.target().asClass();
                if (index.getAllKnownImplementors(NODE_SPEC).contains(cls)) {
                    String typeId = ann.value().asString();
                    registry.put(typeId, cls.name().toString());
                }
            }
        }
        return registry;
    }
}
