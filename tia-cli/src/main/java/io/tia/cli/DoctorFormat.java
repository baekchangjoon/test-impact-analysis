package io.tia.cli;

/** doctor 전용 --format 값 — impact/flaky의 {@link OutputFormat}(4값)과 별개(design spec §3:
 *  summary/markdown은 doctor에 미정의 동작이라 재사용하지 않는다). */
enum DoctorFormat { text, json }
