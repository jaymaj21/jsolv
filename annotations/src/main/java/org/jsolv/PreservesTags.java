package org.jsolv;

import java.lang.annotation.*;

/** The return preserves every label of the indicated one-based argument. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.CLASS)
public @interface PreservesTags {
    int value() default 1;
}
