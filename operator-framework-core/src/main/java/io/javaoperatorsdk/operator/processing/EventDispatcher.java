package io.javaoperatorsdk.operator.processing;

import static io.javaoperatorsdk.operator.EventListUtils.containsCustomResourceDeletedEvent;
import static io.javaoperatorsdk.operator.processing.KubernetesResourceUtils.getUID;
import static io.javaoperatorsdk.operator.processing.KubernetesResourceUtils.getVersion;

import io.fabric8.kubernetes.api.model.KubernetesResourceList;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.javaoperatorsdk.operator.api.Context;
import io.javaoperatorsdk.operator.api.DefaultContext;
import io.javaoperatorsdk.operator.api.DeleteControl;
import io.javaoperatorsdk.operator.api.ResourceController;
import io.javaoperatorsdk.operator.api.UpdateControl;
import io.javaoperatorsdk.operator.processing.event.EventList;
import io.javaoperatorsdk.operator.processing.event.EventSourceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dispatches events to the Controller and handles Finalizers for a single type of Custom Resource.
 */
public class EventDispatcher {

  private static final Logger log = LoggerFactory.getLogger(EventDispatcher.class);

  private final ResourceController controller;
  private final String resourceFinalizer;
  private final CustomResourceFacade customResourceFacade;
  private EventSourceManager eventSourceManager;

  public EventDispatcher(
      ResourceController controller, String finalizer, CustomResourceFacade customResourceFacade) {
    this.controller = controller;
    this.customResourceFacade = customResourceFacade;
    this.resourceFinalizer = finalizer;
  }

  public void setEventSourceManager(EventSourceManager eventSourceManager) {
    this.eventSourceManager = eventSourceManager;
  }

  public PostExecutionControl handleExecution(ExecutionScope executionScope) {
    try {
      return handleDispatch(executionScope);
    } catch (RuntimeException e) {
      log.error("Error during event processing {} failed.", executionScope, e);
      return PostExecutionControl.exceptionDuringExecution(e);
    }
  }

  private PostExecutionControl handleDispatch(ExecutionScope executionScope) {
    CustomResource resource = executionScope.getCustomResource();
    log.debug(
        "Handling events: {} for resource {}", executionScope.getEvents(), resource.getMetadata());

    if (containsCustomResourceDeletedEvent(executionScope.getEvents())) {
      log.debug(
          "Skipping dispatch processing because of a Delete event: {} with version: {}",
          getUID(resource),
          getVersion(resource));
      return PostExecutionControl.defaultDispatch();
    }
    if ((resource.isMarkedForDeletion() && !resource.hasFinalizer(resourceFinalizer))) {
      log.debug(
          "Skipping event dispatching since its marked for deletion but has no finalizer: {}",
          executionScope);
      return PostExecutionControl.defaultDispatch();
    }
    Context context =
        new DefaultContext(
            eventSourceManager,
            new EventList(executionScope.getEvents()),
            executionScope.getRetryInfo());
    if (resource.isMarkedForDeletion()) {
      return handleDelete(resource, context);
    } else {
      return handleCreateOrUpdate(executionScope, resource, context);
    }
  }

  private PostExecutionControl handleCreateOrUpdate(
      ExecutionScope executionScope, CustomResource resource, Context context) {
    if (!resource.hasFinalizer(resourceFinalizer) && !resource.isMarkedForDeletion()) {
      /*  We always add the finalizer if missing and not marked for deletion.
         We execute the controller processing only for processing the event sent as a results
         of the finalizer add. This will make sure that the resources are not created before
         there is a finalizer.
      */
      updateCustomResourceWithFinalizer(resource);
      return PostExecutionControl.onlyFinalizerAdded();
    } else {
      log.debug(
          "Executing createOrUpdate for resource {} with version: {} with execution scope: {}",
          getUID(resource),
          getVersion(resource),
          executionScope);
      UpdateControl<? extends CustomResource> updateControl =
          controller.createOrUpdateResource(resource, context);
      CustomResource updatedCustomResource = null;
      if (updateControl.isUpdateCustomResourceAndStatusSubResource()) {
        updatedCustomResource = updateCustomResource(updateControl.getCustomResource());
        updateControl
            .getCustomResource()
            .getMetadata()
            .setResourceVersion(updatedCustomResource.getMetadata().getResourceVersion());
        updatedCustomResource =
            customResourceFacade.updateStatus(updateControl.getCustomResource());
      } else if (updateControl.isUpdateStatusSubResource()) {
        updatedCustomResource =
            customResourceFacade.updateStatus(updateControl.getCustomResource());
      } else if (updateControl.isUpdateCustomResource()) {
        updatedCustomResource = updateCustomResource(updateControl.getCustomResource());
      }

      if (updatedCustomResource != null) {
        return PostExecutionControl.customResourceUpdated(updatedCustomResource);
      } else {
        return PostExecutionControl.defaultDispatch();
      }
    }
  }

  private PostExecutionControl handleDelete(CustomResource resource, Context context) {
    log.debug(
        "Executing delete for resource: {} with version: {}",
        getUID(resource),
        getVersion(resource));
    DeleteControl deleteControl = controller.deleteResource(resource, context);
    boolean hasFinalizer = resource.hasFinalizer(resourceFinalizer);
    if (deleteControl == DeleteControl.DEFAULT_DELETE && hasFinalizer) {
      CustomResource customResource = removeFinalizer(resource);
      return PostExecutionControl.customResourceUpdated(customResource);
    } else {
      log.debug(
          "Skipping finalizer remove for resource: {} with version: {}. delete control: {}, hasFinalizer: {} ",
          getUID(resource),
          getVersion(resource),
          deleteControl,
          hasFinalizer);
      return PostExecutionControl.defaultDispatch();
    }
  }

  private void updateCustomResourceWithFinalizer(CustomResource resource) {
    log.debug(
        "Adding finalizer for resource: {} version: {}", getUID(resource), getVersion(resource));
    resource.addFinalizer(resourceFinalizer);
    replace(resource);
  }

  private CustomResource updateCustomResource(CustomResource resource) {
    log.debug("Updating resource: {} with version: {}", getUID(resource), getVersion(resource));
    log.trace("Resource before update: {}", resource);
    return replace(resource);
  }

  private CustomResource removeFinalizer(CustomResource resource) {
    log.debug(
        "Removing finalizer on resource: {} with version: {}",
        getUID(resource),
        getVersion(resource));
    resource.removeFinalizer(resourceFinalizer);
    return customResourceFacade.replaceWithLock(resource);
  }

  private CustomResource replace(CustomResource resource) {
    log.debug(
        "Trying to replace resource {}, version: {}",
        resource.getMetadata().getName(),
        resource.getMetadata().getResourceVersion());
    return customResourceFacade.replaceWithLock(resource);
  }

  // created to support unit testing
  public static class CustomResourceFacade<R extends CustomResource> {

    private final MixedOperation<R, KubernetesResourceList<R>, Resource<R>> resourceOperation;

    public CustomResourceFacade(
        MixedOperation<R, KubernetesResourceList<R>, Resource<R>> resourceOperation) {
      this.resourceOperation = resourceOperation;
    }

    public R updateStatus(R resource) {
      log.trace("Updating status for resource: {}", resource);
      return resourceOperation
          .inNamespace(resource.getMetadata().getNamespace())
          .withName(resource.getMetadata().getName())
          .updateStatus(resource);
    }

    public R replaceWithLock(R resource) {
      return resourceOperation
          .inNamespace(resource.getMetadata().getNamespace())
          .withName(resource.getMetadata().getName())
          .lockResourceVersion(resource.getMetadata().getResourceVersion())
          .replace(resource);
    }
  }
}
