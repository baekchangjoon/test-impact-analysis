package io.tia.core.config;

/** tia.yml 로딩/검증 오류 — 메시지에 파일 경로와 원인을 담는다(fail-fast, exit 1 대상). */
public class TiaConfigException extends RuntimeException {
    public TiaConfigException(String message) { super(message); }
    public TiaConfigException(String message, Throwable cause) { super(message, cause); }
}
