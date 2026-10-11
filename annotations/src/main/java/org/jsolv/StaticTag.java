package org.jsolv;

import java.lang.annotation.*;

/** Marks an annotation as a value-flow label understood by the checker. */
@Target(ElementType.ANNOTATION_TYPE)
@Retention(RetentionPolicy.CLASS)
public @interface StaticTag {}
