package com.guildworkman.api.signing.custody;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.stellar.sdk.KeyPair;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A build-time guard on the custody boundary's <em>shape</em>, not its
 * behaviour.
 *
 * <p>Every other test here asserts that the providers don't leak a key today.
 * These assert that they can't grow a way to leak one tomorrow: the interface
 * is pinned to four methods, and no implementation may expose a public member
 * that hands back key material or is even named as though it might. A getter
 * added in six months for what looks like a good reason fails here, in a test
 * whose failure message says why, rather than passing review as an innocuous
 * accessor.
 *
 * <p>This is deliberately broader than {@code SigningProvider} itself, since
 * the interface is only half the surface — nothing stops a caller holding a
 * concrete {@link LocalSigningProvider} and calling a method the interface
 * never declared.
 */
class SigningProviderShapeTest {

    /**
     * Names that suggest a method hands back something private. Matched as
     * substrings, case-insensitively, so {@code getSecretSeed},
     * {@code exportPrivateKey} and {@code keyPairFor} are all caught.
     */
    private static final List<String> FORBIDDEN_NAME_FRAGMENTS =
            List.of("secret", "seed", "private", "keypair", "signingkey", "rawkey");

    /** Types no public custody method may ever return. */
    private static final List<Class<?>> FORBIDDEN_RETURN_TYPES = List.of(KeyPair.class, char[].class);

    private static final List<Class<?>> IMPLEMENTATIONS =
            List.of(LocalSigningProvider.class, KmsSigningProvider.class);

    /**
     * The interface's method set, pinned exactly. Adding a fifth method is a
     * decision that has to be made here first.
     */
    @Test
    void theInterfaceExposesExactlyTheFourMethodsItIsAllowedTo() {
        assertThat(SigningProvider.class.getDeclaredMethods())
                .extracting(Method::getName)
                .containsExactlyInAnyOrder("providerId", "supports", "publicKey", "sign");
    }

    @ParameterizedTest
    @ValueSource(classes = {LocalSigningProvider.class, KmsSigningProvider.class})
    void noImplementationExposesAMethodShapedLikeAKeyGetter(Class<?> implementation) {
        List<Method> suspicious = Arrays.stream(implementation.getMethods())
                .filter(method -> method.getDeclaringClass() != Object.class)
                .filter(SigningProviderShapeTest::looksLikeItReturnsKeyMaterial)
                .toList();

        assertThat(suspicious)
                .withFailMessage("%s exposes %s. Nothing above the custody boundary may be able to read key "
                                + "material, or a value named as though it could — see SigningProvider's contract.",
                        implementation.getSimpleName(), suspicious)
                .isEmpty();
    }

    /**
     * A public field would bypass the method check entirely, and Lombok's
     * {@code @Getter}/{@code @Setter} on a provider would generate one of each.
     */
    @ParameterizedTest
    @ValueSource(classes = {LocalSigningProvider.class, KmsSigningProvider.class})
    void noImplementationExposesAPublicOrProtectedField(Class<?> implementation) {
        assertThat(Arrays.stream(implementation.getDeclaredFields())
                .filter(field -> Modifier.isPublic(field.getModifiers()) || Modifier.isProtected(field.getModifiers()))
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .toList())
                .withFailMessage("%s exposes instance state directly; custody state must stay private",
                        implementation.getSimpleName())
                .isEmpty();
    }

    /**
     * The providers hold their keys (or their gateway credential) in instance
     * state, so a generated {@code toString} would print them. Both override
     * it; this asserts the override exists rather than trusting that the next
     * person notices.
     */
    @Test
    void everyImplementationOverridesToString() {
        for (Class<?> implementation : IMPLEMENTATIONS) {
            assertThat(Arrays.stream(implementation.getDeclaredMethods())
                    .anyMatch(method -> "toString".equals(method.getName()) && method.getParameterCount() == 0))
                    .withFailMessage("%s inherits Object.toString(); a custody class must control its own rendering",
                            implementation.getSimpleName())
                    .isTrue();
        }
    }

    private static boolean looksLikeItReturnsKeyMaterial(Method method) {
        if (FORBIDDEN_RETURN_TYPES.contains(method.getReturnType())) {
            return true;
        }
        String name = method.getName().toLowerCase(Locale.ROOT);
        return FORBIDDEN_NAME_FRAGMENTS.stream().anyMatch(name::contains);
    }
}
