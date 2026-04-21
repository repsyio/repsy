package io.repsy.os.server.protocols.shared.aop.config;

import io.repsy.protocols.shared.repo.dtos.Permission;
import io.repsy.protocols.shared.repo.dtos.RepoScope;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RepoOperation {

  RepoScope scope() default RepoScope.ALL;

  Permission permission() default Permission.READ;
}
