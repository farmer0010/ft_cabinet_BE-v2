package com.gyeongsan.cabinet.global.aspect;

import jakarta.servlet.http.HttpServletRequest;
import java.time.temporal.Temporal;
import java.util.Arrays;
import java.util.stream.Collectors;
import lombok.extern.log4j.Log4j2;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Aspect
@Component
@Log4j2
public class LoggingAspect {

    @Pointcut("execution(* com.gyeongsan.cabinet..*Controller.*(..))")
    public void controllerMethods() {}

    @Around("controllerMethods()")
    public Object logExecutionTime(ProceedingJoinPoint joinPoint) throws Throwable {
        long startTime = System.currentTimeMillis();

        HttpServletRequest request =
                ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes())
                        .getRequest();

        String method = request.getMethod();
        String requestURI = request.getRequestURI();

        log.info(
                "👉 [REQUEST] {} {} | Params: {}",
                method,
                requestURI,
                summarize(joinPoint.getArgs()));

        Object result = joinPoint.proceed();

        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;

        log.info("✅ [RESPONSE] {} {} | Time: {}ms", method, requestURI, duration);

        if (duration > 2000) {
            log.warn("⚠️ [SLOW QUERY] {} took {}ms", requestURI, duration);
        }

        return result;
    }

    static String summarize(Object[] args) {
        if (args == null || args.length == 0) {
            return "[]";
        }
        return Arrays.stream(args)
                .map(LoggingAspect::describe)
                .collect(Collectors.joining(", ", "[", "]"));
    }

    static String describe(Object arg) {
        if (arg == null) {
            return "null";
        }
        if (arg instanceof Number
                || arg instanceof Boolean
                || arg instanceof Enum<?>
                || arg instanceof Temporal) {
            return String.valueOf(arg);
        }
        return arg.getClass().getSimpleName();
    }
}
