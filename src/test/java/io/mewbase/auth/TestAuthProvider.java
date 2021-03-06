package io.mewbase.auth;

import io.mewbase.bson.BsonObject;
import io.mewbase.client.MewException;
import io.mewbase.server.MewbaseAuthProvider;
import io.mewbase.server.MewbaseUser;

import java.util.concurrent.CompletableFuture;

public class TestAuthProvider implements MewbaseAuthProvider {

    @Override
    public CompletableFuture<MewbaseUser> authenticate(BsonObject authInfo) {
        CompletableFuture cf = new CompletableFuture();

        boolean success = authInfo.getBoolean("success");
        boolean throwAuthorisationEx = authInfo.getBoolean("throwAuthorisationEx");

        if (!success) {
            cf.completeExceptionally(new MewException("Incorrect username/password"));
        } else {
            boolean isAuthorised = authInfo.getBoolean("isAuthorised");
            cf.complete(new TestUser(isAuthorised, throwAuthorisationEx));
        }

        return cf;
    }
}
