package edu.stdiscm.consumer;

import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GrpcServer {
  private Server server;

  @Bean(destroyMethod = "close")
  UploadScheduler uploadScheduler(MediaSettings settings) {
    return new UploadScheduler(settings.consumers(), settings.queueCapacity());
  }

  @Bean
  UploadService uploadService(UploadScheduler scheduler, VideoStore store, MediaSettings settings)
      throws IOException {
    var service = new UploadService(scheduler, store, settings);
    server =
        NettyServerBuilder.forPort(settings.grpcPort())
            .maxInboundMessageSize(UploadService.CHUNK_BYTES + 1024)
            .maxConcurrentCallsPerConnection(settings.consumers() + settings.queueCapacity() + 16)
            .addService(service)
            .build()
            .start();
    return service;
  }

  @PreDestroy
  void stop() throws InterruptedException {
    if (server != null) {
      server.shutdownNow();
      server.awaitTermination(5, TimeUnit.SECONDS);
    }
  }
}
