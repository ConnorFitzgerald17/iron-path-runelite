package gg.ironpath;

import com.google.gson.Gson;
import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;
import javax.inject.Inject;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

final class IronPathApiClient
{
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient httpClient;
    private final Gson gson;
    private final IronPathConfig config;

    @Inject
    IronPathApiClient(OkHttpClient httpClient, Gson gson, IronPathConfig config)
    {
        this.httpClient = httpClient;
        this.gson = gson;
        this.config = config;
    }

    void exchangeCode(String code, String characterName, Consumer<IronPathDtos.LinkResponse> callback)
    {
        IronPathDtos.LinkRequest payload = new IronPathDtos.LinkRequest(code, characterName, "0.4.0");
        post("/api/plugin/v1/link/exchange", null, gson.toJson(payload), response ->
        {
            IronPathDtos.LinkResponse result = response.body == null
                ? new IronPathDtos.LinkResponse()
                : gson.fromJson(response.body, IronPathDtos.LinkResponse.class);
            if (!response.success && result.error == null)
            {
                result.error = "The linking code was rejected.";
            }
            callback.accept(result);
        });
    }

    void sendSnapshot(String token, IronPathDtos.Snapshot snapshot, Consumer<Boolean> callback)
    {
        post("/api/plugin/v1/snapshot", token, gson.toJson(snapshot), response -> callback.accept(response.success));
    }

    void sendLoot(String token, List<IronPathDtos.LootEvent> events, Consumer<Boolean> callback)
    {
        post("/api/plugin/v1/loot-events", token, gson.toJson(new IronPathDtos.LootBatch(events)), response -> callback.accept(response.success));
    }

    void sendCollectionLogSection(String token, IronPathDtos.CollectionLogSection section, Consumer<Boolean> callback)
    {
        post("/api/plugin/v1/collection-log", token, gson.toJson(section), response -> callback.accept(response.success));
    }

    void fetchGoals(String token, Consumer<IronPathDtos.GoalsResponse> callback)
    {
        Request request = requestBuilder("/api/plugin/v1/goals", token).get().build();
        httpClient.newCall(request).enqueue(new Callback()
        {
            @Override
            public void onFailure(Call call, IOException error)
            {
                callback.accept(null);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException
            {
                try (Response closeable = response)
                {
                    if (!response.isSuccessful() || response.body() == null)
                    {
                        callback.accept(null);
                        return;
                    }
                    callback.accept(gson.fromJson(response.body().charStream(), IronPathDtos.GoalsResponse.class));
                }
            }
        });
    }

    void updateGoalStatus(String token, String goalId, String status, Consumer<Boolean> callback)
    {
        Request request = requestBuilder("/api/plugin/v1/goals/" + goalId, token)
            .patch(RequestBody.create(JSON, gson.toJson(new IronPathDtos.GoalStatusUpdate(status))))
            .build();
        send(request, response -> callback.accept(response.success));
    }

    void unlink(String token, Consumer<Boolean> callback)
    {
        post("/api/plugin/v1/unlink", token, "{}", response -> callback.accept(response.success));
    }

    private Request.Builder requestBuilder(String path, String token)
    {
        String origin = config.apiOrigin().replaceAll("/+$", "");
        Request.Builder builder = new Request.Builder()
            .url(origin + path)
            .header("User-Agent", "IronPath-RuneLite/0.4.0");
        if (token != null && !token.isEmpty())
        {
            builder.header("Authorization", "Bearer " + token);
        }
        return builder;
    }

    private void post(String path, String token, String json, Consumer<ApiResponse> callback)
    {
        Request request = requestBuilder(path, token).post(RequestBody.create(JSON, json)).build();
        send(request, callback);
    }

    private void send(Request request, Consumer<ApiResponse> callback)
    {
        httpClient.newCall(request).enqueue(new Callback()
        {
            @Override
            public void onFailure(Call call, IOException error)
            {
                callback.accept(new ApiResponse(false, null));
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException
            {
                try (Response closeable = response)
                {
                    callback.accept(new ApiResponse(response.isSuccessful(), response.body() == null ? null : response.body().string()));
                }
            }
        });
    }

    private static final class ApiResponse
    {
        private final boolean success;
        private final String body;
        private ApiResponse(boolean success, String body) { this.success = success; this.body = body; }
    }
}
