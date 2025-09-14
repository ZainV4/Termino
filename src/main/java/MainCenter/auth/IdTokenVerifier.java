package MainCenter.auth;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.RemoteJWKSet;
import com.nimbusds.jose.proc.JWSAlgorithmFamilyJWSKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;

import java.net.URL;
import java.time.Instant;
import java.util.List;

public final class IdTokenVerifier {
    private final String issuer;       // e.g. https://cognito-idp.us-east-2.amazonaws.com/us-east-2_HQPRSNjPZ
    private final String clientId;     // your app client id (audience)
    private final ConfigurableJWTProcessor<SecurityContext> proc;

    public IdTokenVerifier(String jwksUrl, String issuer, String clientId) throws Exception {
        this.issuer = issuer;
        this.clientId = clientId;

        JWKSource<SecurityContext> jwkSrc = new RemoteJWKSet<>(new URL(jwksUrl));
        this.proc = new DefaultJWTProcessor<>();
        this.proc.setJWSKeySelector(new JWSAlgorithmFamilyJWSKeySelector<>(
                JWSAlgorithm.Family.RSA, jwkSrc)); // Cognito uses RS256 by default
    }

    public JWTClaimsSet verify(String idToken) throws Exception {
        SignedJWT jwt = SignedJWT.parse(idToken);
        JWTClaimsSet claims = proc.process(jwt, null);

        // Basic claim checks
        if (!issuer.equals(claims.getIssuer()))
            throw new IllegalArgumentException("Bad iss");

        List<String> aud = claims.getAudience();
        if (aud == null || !aud.contains(clientId))
            throw new IllegalArgumentException("Bad aud");

        if (claims.getExpirationTime() == null ||
                Instant.now().isAfter(claims.getExpirationTime().toInstant()))
            throw new IllegalArgumentException("Expired");

        return claims;
    }
}
