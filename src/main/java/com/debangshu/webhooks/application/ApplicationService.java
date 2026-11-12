package com.debangshu.webhooks.application;

import com.debangshu.webhooks.application.ApplicationDtos.ApplicationKeyResponse;
import com.debangshu.webhooks.application.ApplicationDtos.ApplicationResponse;
import com.debangshu.webhooks.application.ApplicationDtos.CreateApplicationRequest;
import com.debangshu.webhooks.common.NotFoundException;
import com.debangshu.webhooks.common.PageResponse;
import com.debangshu.webhooks.config.AppProperties;
import com.debangshu.webhooks.security.ApiKeys;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

@Service
public class ApplicationService {

    private final ApplicationRepository applications;
    private final AppProperties properties;
    private final Clock clock;

    public ApplicationService(ApplicationRepository applications, AppProperties properties, Clock clock) {
        this.applications = applications;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public ApplicationKeyResponse create(CreateApplicationRequest request) {
        String apiKey = ApiKeys.generate();
        int rateLimit = request.rateLimitPerSecond() != null
                ? request.rateLimitPerSecond()
                : properties.rateLimit().defaultEventsPerSecond();
        Application application = applications.save(new Application(request.name().trim(), rateLimit,
                ApiKeys.displayPrefix(apiKey), ApiKeys.hash(apiKey), clock.instant()));
        return ApplicationKeyResponse.from(application, apiKey);
    }

    @Transactional(readOnly = true)
    public PageResponse<ApplicationResponse> list(Pageable pageable) {
        return PageResponse.from(applications.findAll(pageable).map(ApplicationResponse::from));
    }

    @Transactional(readOnly = true)
    public ApplicationResponse get(long id) {
        return ApplicationResponse.from(find(id));
    }

    /** The old key stops working straight away (there is no grace period for API keys). */
    @Transactional
    public ApplicationKeyResponse rotateApiKey(long id) {
        Application application = find(id);
        String apiKey = ApiKeys.generate();
        application.replaceApiKey(ApiKeys.displayPrefix(apiKey), ApiKeys.hash(apiKey), clock.instant());
        return ApplicationKeyResponse.from(application, apiKey);
    }

    /** ON DELETE CASCADE in the schema removes the application's endpoints, events and deliveries too. */
    @Transactional
    public void delete(long id) {
        applications.delete(find(id));
    }

    private Application find(long id) {
        return applications.findById(id).orElseThrow(() -> new NotFoundException("Application", id));
    }
}
