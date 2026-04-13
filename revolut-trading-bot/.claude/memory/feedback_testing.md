---
name: Testing framework preference
description: User wants Spock/Groovy-only tests — no Mockito, no JUnit annotations
type: feedback
---

Use pure Spock/Groovy tests only. No Mockito (`@MockBean`, `@MockitoBean`, `when().thenReturn()`, `verify()`), no JUnit (`@Test`, `@SpringBootTest`), no `@WebMvcTest`.

**Why:** User's explicit preference — the whole test suite should be idiomatic Spock using `Mock()`, `Stub()`, `>>`, `1 *`, etc.

**How to apply:**
- For controller tests: instantiate the controller directly with Spock `Mock()` collaborators, call methods, assert on the returned `ResponseEntity`
- Never import `org.mockito.*` or `org.junit.*` in test files
- Never use `@MockitoBean`, `@MockBean`, `@WebMvcTest`, `@SpringBootTest`
- Do not set up Spring application contexts in tests — pure unit tests only
