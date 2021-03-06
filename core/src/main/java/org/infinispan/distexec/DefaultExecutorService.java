package org.infinispan.distexec;

import java.io.Externalizable;
import java.io.NotSerializableException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RunnableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.infinispan.AdvancedCache;
import org.infinispan.Cache;
import org.infinispan.commands.CancelCommand;
import org.infinispan.commands.CancellationService;
import org.infinispan.commands.CommandsFactory;
import org.infinispan.commands.read.DistributedExecuteCommand;
import org.infinispan.commons.marshall.Marshaller;
import org.infinispan.commons.marshall.StreamingMarshaller;
import org.infinispan.commons.util.Util;
import org.infinispan.configuration.cache.Configuration;
import org.infinispan.distexec.spi.DistributedTaskLifecycleService;
import org.infinispan.distribution.DistributionInfo;
import org.infinispan.distribution.DistributionManager;
import org.infinispan.factories.ComponentRegistry;
import org.infinispan.factories.threads.DefaultThreadFactory;
import org.infinispan.interceptors.AsyncInterceptor;
import org.infinispan.interceptors.AsyncInterceptorChain;
import org.infinispan.interceptors.locking.ClusteringDependentLogic;
import org.infinispan.lifecycle.ComponentStatus;
import org.infinispan.manager.EmbeddedCacheManager;
import org.infinispan.remoting.inboundhandler.DeliverOrder;
import org.infinispan.remoting.responses.Response;
import org.infinispan.remoting.responses.SuccessfulResponse;
import org.infinispan.remoting.rpc.RpcManager;
import org.infinispan.remoting.rpc.RpcOptions;
import org.infinispan.remoting.transport.Address;
import org.infinispan.remoting.transport.TopologyAwareAddress;
import org.infinispan.remoting.transport.impl.SingleResponseCollector;
import org.infinispan.remoting.transport.jgroups.SuspectException;
import org.infinispan.security.AuthorizationManager;
import org.infinispan.security.AuthorizationPermission;
import org.infinispan.commons.time.TimeService;
import org.infinispan.util.concurrent.CompletableFutures;
import org.infinispan.util.logging.Log;
import org.infinispan.util.logging.LogFactory;

/**
 * Infinispan's implementation of an {@link ExecutorService} and {@link DistributedExecutorService}.
 * This ExecutorService provides methods to submit tasks for an execution on a cluster of Infinispan
 * nodes.
 * <p>
 *
 *
 * Note that due to potential task migration to another nodes every {@link Callable},
 * {@link Runnable} and/or {@link DistributedCallable} submitted must be either {@link Serializable}
 * or {@link Externalizable}. Also the value returned from a callable must be {@link Serializable}
 * or {@link Externalizable}. Unfortunately if the value returned is not serializable then a
 * {@link NotSerializableException} will be thrown.
 *
 * @author Vladimir Blagojevic
 * @since 5.0
 * @deprecated since 9.1 Please use {@link EmbeddedCacheManager#executor()} or {@link org.infinispan.CacheStream} instead.
 */
public class DefaultExecutorService extends AbstractExecutorService implements DistributedExecutorService {

   private static final NodeFilter SAME_MACHINE_FILTER = TopologyAwareAddress::isSameMachine;

   private static final NodeFilter SAME_RACK_FILTER = TopologyAwareAddress::isSameRack;

   private static final NodeFilter SAME_SITE_FILTER = TopologyAwareAddress::isSameSite;

   private static final NodeFilter ALL_FILTER = (thisAddress, otherAddress) -> true;

   public static final DistributedTaskFailoverPolicy NO_FAILOVER = new NoTaskFailoverPolicy();
   public static final DistributedTaskFailoverPolicy RANDOM_NODE_FAILOVER = new RandomNodeTaskFailoverPolicy();

   private static final Log log = LogFactory.getLog(DefaultExecutorService.class);
   private static final boolean trace = log.isTraceEnabled();
   protected final AtomicBoolean isShutdown = new AtomicBoolean(false);
   protected final AdvancedCache cache;
   protected final RpcManager rpc;
   protected final AsyncInterceptorChain invoker;
   protected final CommandsFactory factory;
   protected final Marshaller marshaller;
   protected final ExecutorService localExecutorService;
   protected final CancellationService cancellationService;
   protected final ClusteringDependentLogic clusterDependentLogic;
   protected final boolean takeExecutorOwnership;
   private final TimeService timeService;

   /**
    * Creates a new DefaultExecutorService given a master cache node for local task execution. All
    * distributed task executions will be initiated from this Infinispan cache node
    *
    * @param masterCacheNode
    *           Cache node initiating distributed task
    */
   public DefaultExecutorService(Cache<?, ?> masterCacheNode) {
      this(masterCacheNode, createLocalExecutor(masterCacheNode), true);
   }

   public static ExecutorService createLocalExecutor(Cache<?, ?> masterCacheNode) {
      String nodeName = masterCacheNode != null ? SecurityActions.getConfiguredNodeName(masterCacheNode) : null;
      return Executors.newSingleThreadExecutor(
            new DefaultThreadFactory(null, Thread.NORM_PRIORITY, DefaultThreadFactory.DEFAULT_PATTERN, nodeName,
                                     "DefaultExecutorService"));
   }

   /**
    * Creates a new DefaultExecutorService given a master cache node and an ExecutorService for
    * parallel execution of tasks ran on this node. All distributed task executions will be
    * initiated from this Infinispan cache node.
    * <p>
    * Note that DefaultExecutorService will not shutdown client supplied localExecutorService once
    * this DefaultExecutorService is shutdown. Lifecycle management of a supplied ExecutorService is
    * left to the client
    *
    * Also note that client supplied ExecutorService should not execute tasks in the caller's thread
    * ( i.e rejectionHandler of {@link ThreadPoolExecutor} configured with {link
    * {@link ThreadPoolExecutor.CallerRunsPolicy})
    *
    * @param masterCacheNode
    *           Cache node initiating distributed task
    * @param localExecutorService
    *           ExecutorService to run local tasks
    */
   public DefaultExecutorService(Cache<?, ?> masterCacheNode, ExecutorService localExecutorService) {
      this(masterCacheNode, localExecutorService, false);
   }

   /**
    * Creates a new DefaultExecutorService given a master cache node and an ExecutorService for
    * parallel execution of task ran on this node. All distributed task executions will be initiated
    * from this Infinispan cache node.
    *
    * @param masterCacheNode
    *           Cache node initiating distributed task
    * @param localExecutorService
    *           ExecutorService to run local tasks
    * @param takeExecutorOwnership
    *           if true {@link DistributedExecutorService#shutdown()} and
    *           {@link DistributedExecutorService#shutdownNow()} method will shutdown
    *           localExecutorService as well
    *
    */
   public DefaultExecutorService(Cache<?, ?> masterCacheNode, ExecutorService localExecutorService,
            boolean takeExecutorOwnership) {
      super();
      if (masterCacheNode == null)
         throw new IllegalArgumentException("Can not use null cache for DefaultExecutorService");
      else if (localExecutorService == null)
         throw new IllegalArgumentException("Can not use null instance of ExecutorService");
      else {
         try {
            if (localExecutorService.isShutdown())
               throw new IllegalArgumentException("Can not use an instance of ExecutorService which is shutdown");
         } catch (IllegalStateException e) {
            if (takeExecutorOwnership) {
               throw new IllegalArgumentException("Can not take ownership of a ManagedExecutorService");
            }
         }
      }

      this.cache = masterCacheNode.getAdvancedCache();
      ComponentRegistry registry = SecurityActions.getCacheComponentRegistry(cache);

      ensureAccessPermissions(cache);
      ensureProperCacheState(cache);
      ensureFullCache(cache);


      this.rpc = SecurityActions.getCacheRpcManager(cache);
      this.invoker = registry.getComponent(AsyncInterceptorChain.class);
      this.factory = registry.getComponent(CommandsFactory.class);
      this.marshaller = registry.getComponent(StreamingMarshaller.class);
      this.cancellationService = registry.getComponent(CancellationService.class);
      this.localExecutorService = localExecutorService;
      this.takeExecutorOwnership = takeExecutorOwnership;
      this.timeService = registry.getTimeService();
      this.clusterDependentLogic = registry.getComponent(ClusteringDependentLogic.class);
   }

   @Override
   public <T> DistributedTaskBuilder<T> createDistributedTaskBuilder(Callable<T> callable) {
      Configuration cacheConfiguration = SecurityActions.getCacheConfiguration(cache);
      long to = cacheConfiguration.clustering().remoteTimeout();
      DistributedTaskBuilder<T> dtb = new DefaultDistributedTaskBuilder<>(to);
      dtb.callable(callable);
      return dtb;
   }

   @Override
   public <T> CompletableFuture<T> submit(Runnable task, T result) {
      return (CompletableFuture<T>) super.submit(task, result);
   }

   @Override
   public <T> CompletableFuture<T> submit(Callable<T> task) {
      return (CompletableFuture<T>) super.submit(task);
   }

   @Override
   public void shutdown() {
      realShutdown(false);
   }

   protected List<Address> getMembers() {
      if (rpc != null) {
         return rpc.getMembers();
      } else {
         return Collections.singletonList(getAddress());
      }
   }

   protected <T> List<Address> executionCandidates(DistributedTask<T> task) {
      return filterMembers(task.getTaskExecutionPolicy(), getMembers());
   }

   private Address getAddress() {
      return clusterDependentLogic.getAddress();
   }

   private List<Runnable> realShutdown(boolean interrupt) {
      isShutdown.set(true);
      // TODO cancel all tasks
      if (takeExecutorOwnership) {
         if (interrupt)
            localExecutorService.shutdownNow();
         else
            localExecutorService.shutdown();
      }
      return Collections.emptyList();
   }

   @Override
   public List<Runnable> shutdownNow() {
      return realShutdown(true);
   }

   @Override
   public boolean isShutdown() {
      return isShutdown.get();
   }

   @Override
   public boolean isTerminated() {
      if (isShutdown.get()) {
         // TODO account for all tasks
         return true;
      }
      return false;
   }

   @Override
   public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
      //long nanoTimeWait = unit.toNanos(timeout);
      // TODO wait for all tasks to finish
      return true;
   }

   @Override
   public <T> T invokeAny(Collection<? extends Callable<T>> tasks) throws InterruptedException,
            ExecutionException {
      try {
         return doInvokeAny(tasks, false, 0);
      } catch (TimeoutException cannotHappen) {
         assert false;
         return null;
      }
   }

   @Override
   public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
      return doInvokeAny(tasks, true, unit.toNanos(timeout));
   }

   /**
    * the main mechanics of invokeAny. This was essentially copied from
    * {@link AbstractExecutorService} doInvokeAny except that we replaced the
    * {@link ExecutorCompletionService} with our {@link DistributedExecutionCompletionService}.
    */
   private <T> T doInvokeAny(Collection<? extends Callable<T>> tasks, boolean timed, long nanos)
            throws InterruptedException, ExecutionException, TimeoutException {
      if (tasks == null)
         throw new NullPointerException();
      int ntasks = tasks.size();
      if (ntasks == 0)
         throw new IllegalArgumentException();
      List<Future<T>> futures = new ArrayList<>(ntasks);
      CompletionService<T> ecs = new DistributedExecutionCompletionService<>(this);

      // For efficiency, especially in executors with limited
      // parallelism, check to see if previously submitted tasks are
      // done before submitting more of them. This interleaving
      // plus the exception mechanics account for messiness of main
      // loop.

      try {
         // Record exceptions so that if we fail to obtain any
         // result, we can throw the last exception we got.
         ExecutionException ee = null;
         long lastTime = (timed) ? timeService.time() : 0;
         Iterator<? extends Callable<T>> it = tasks.iterator();

         // Start one task for sure; the rest incrementally
         futures.add(ecs.submit(it.next()));
         --ntasks;
         int active = 1;

         for (;;) {
            Future<T> f = ecs.poll();
            if (f == null) {
               if (ntasks > 0) {
                  --ntasks;
                  futures.add(ecs.submit(it.next()));
                  ++active;
               } else if (active == 0)
                  break;
               else if (timed) {
                  f = ecs.poll(nanos, TimeUnit.NANOSECONDS);
                  if (f == null)
                     throw new TimeoutException();
                  long now = timeService.time();
                  nanos -= timeService.timeDuration(lastTime, now, TimeUnit.NANOSECONDS);
                  lastTime = now;
               } else
                  f = ecs.take();
            }
            if (f != null) {
               --active;
               try {
                  return f.get();
               } catch (InterruptedException ie) {
                  throw ie;
               } catch (ExecutionException eex) {
                  ee = eex;
               } catch (RuntimeException rex) {
                  ee = new ExecutionException(rex);
               }
            }
         }

         if (ee == null)
            ee = new ExecutionException() {
               private static final long serialVersionUID = 200818694545553992L;
            };
         throw ee;

      } finally {
         for (Future<T> f : futures)
            f.cancel(true);
      }
   }

   @Override
   public void execute(Runnable command) {
      if (!isShutdown.get()) {
         DistributedTaskPart<Object> cmd;
         if (command instanceof DistributedTaskPart<?>) {
            cmd = (DistributedTaskPart<Object>) command;
         } else if (command instanceof Serializable) {
            cmd = (DistributedTaskPart<Object>) newTaskFor(command, null);
         } else {
            throw new IllegalArgumentException("Runnable command is not Serializable  " + command);
         }
         cmd.execute();
      } else {
         throw new RejectedExecutionException();
      }
   }

   @Override
   protected <T> RunnableFuture<T> newTaskFor(Runnable runnable, T value) {
      if (runnable == null) throw new NullPointerException();
      RunnableAdapter<T> adapter = new RunnableAdapter<>(runnable, value);
      return newTaskFor(adapter);
   }

   @Override
   protected <T> RunnableFuture<T> newTaskFor(Callable<T> callable) {
      if (callable == null) throw new NullPointerException();
      DistributedTaskBuilder<T> distributedTaskBuilder = createDistributedTaskBuilder(callable);
      DistributedTask<T> task = distributedTaskBuilder.build();
      DistributedExecuteCommand<T> executeCommand = factory.buildDistributedExecuteCommand(
               callable, getAddress(), null);
      return createDistributedTaskPart(task, executeCommand, selectExecutionNode(task), 0);
   }

   @Override
   public <T> CompletableFuture<T> submit(Address target, Callable<T> task) {
      DistributedTaskBuilder<T> distributedTaskBuilder = createDistributedTaskBuilder(task);
      DistributedTask<T> distributedTask = distributedTaskBuilder.build();
      return submit(target, distributedTask);
   }

   @Override
   public <T> CompletableFuture<T> submit(Address target, DistributedTask<T> task) {
      if (task == null)
         throw new NullPointerException();
      if (target == null)
         throw new NullPointerException();
      List<Address> members = getMembers();
      if (!members.contains(target)) {
         return CompletableFutures.completedExceptionFuture(new SuspectException("Target node " + target
                  + " is not a cluster member, members are " + members));
      }
      Address me = getAddress();
      DistributedExecuteCommand<T> c;
      if (target.equals(me)) {
         c = factory.buildDistributedExecuteCommand(clone(task.getCallable()), me, null);
      } else {
         c = factory.buildDistributedExecuteCommand(task.getCallable(), me, null);
      }
      DistributedTaskPart<T> part = createDistributedTaskPart(task, c, target, 0);
      part.execute();
      return part;
   }

   @Override
   public <T, K> CompletableFuture<T> submit(Callable<T> task, K... input) {
      DistributedTaskBuilder<T> distributedTaskBuilder = createDistributedTaskBuilder(task);
      DistributedTask<T> distributedTask = distributedTaskBuilder.build();
      return submit(distributedTask, input);
   }

   @Override
   public <T, K> CompletableFuture<T> submit(DistributedTask<T> task, K... input) {
      if (task == null) throw new NullPointerException();

      Address me = getAddress();
      if(inputKeysSpecified(input)){
         Map<Address, List<K>> nodesKeysMap = keysToExecutionNodes(task.getTaskExecutionPolicy(), input);
         checkExecutionPolicy(task, nodesKeysMap, input);
         ArrayList<Address> nodes = new ArrayList<>(nodesKeysMap.keySet());
         Address target = selectExecutionNode(nodes);
         DistributedExecuteCommand<T> c;
         if (target.equals(me)) {
            c = factory.buildDistributedExecuteCommand(clone(task.getCallable()), me, Arrays.asList(input));
         } else {
            c = factory.buildDistributedExecuteCommand(task.getCallable(), me, Arrays.asList(input));
         }
         DistributedTaskPart<T> part = createDistributedTaskPart(task, c, Arrays.asList(input), target, 0);
         part.execute();
         return part;
      } else {
         return submit(me, task);
      }
   }

   @Override
   public <T> List<CompletableFuture<T>> submitEverywhere(Callable<T> task) {
      DistributedTaskBuilder<T> distributedTaskBuilder = createDistributedTaskBuilder(task);
      DistributedTask<T> distributedTask = distributedTaskBuilder.build();
      return submitEverywhere(distributedTask);
   }

   @Override
   public <T> List<CompletableFuture<T>> submitEverywhere(DistributedTask<T> task) {
      if (task == null) throw new NullPointerException();

      List<Address> members = executionCandidates(task);
      List<CompletableFuture<T>> futures = new ArrayList<>(members.size());
      Address me = getAddress();
      for (Address target : members) {
         DistributedExecuteCommand<T> c;
         if (target.equals(me)) {
            c = factory.buildDistributedExecuteCommand(clone(task.getCallable()), me, null);
         } else {
            c = factory.buildDistributedExecuteCommand(task.getCallable(), me, null);
         }
         DistributedTaskPart<T> part = createDistributedTaskPart(task, c, target, 0);
         futures.add(part);
         part.execute();
      }
      return futures;
   }

   @Override
   public <T, K> List<CompletableFuture<T>> submitEverywhere(Callable<T> task, K... input) {
      DistributedTaskBuilder<T> distributedTaskBuilder = createDistributedTaskBuilder(task);
      DistributedTask<T> distributedTask = distributedTaskBuilder.build();
      return submitEverywhere(distributedTask, input);
   }

   @Override
   public <T, K> List<CompletableFuture<T>> submitEverywhere(DistributedTask<T> task, K... input) {
      if (task == null) throw new NullPointerException();
      if(inputKeysSpecified(input)) {
         List<CompletableFuture<T>> futures = new ArrayList<>(input.length * 2);
         Address me = getAddress();
         Map<Address, List<K>> nodesKeysMap = keysToExecutionNodes(task.getTaskExecutionPolicy(), input);
         checkExecutionPolicy(task, nodesKeysMap, input);
         for (Entry<Address, List<K>> e : nodesKeysMap.entrySet()) {
            Address target = e.getKey();
            DistributedExecuteCommand<T> c;
            if (target.equals(me)) {
               c = factory.buildDistributedExecuteCommand(clone(task.getCallable()), me, e.getValue());
            } else {
               c = factory.buildDistributedExecuteCommand(task.getCallable(), me, e.getValue());
            }
            DistributedTaskPart<T> part = createDistributedTaskPart(task, c, e.getValue(), target, 0);
            futures.add(part);
            part.execute();
         }
         return futures;
      } else {
         return submitEverywhere(task);
      }
   }

   protected <T> Callable<T> clone(Callable<T> task){
     return Util.cloneWithMarshaller(marshaller, task);
   }

   protected <T, K> DistributedTaskPart<T> createDistributedTaskPart(DistributedTask<T> task,
            DistributedExecuteCommand<T> c, List<K> inputKeys, Address target,
            int failoverCount) {
      return getAddress().equals(target) ?
            new LocalDistributedTaskPart<>(task, c, (List<Object>) inputKeys, failoverCount) :
            new RemoteDistributedTaskPart<>(task, c, (List<Object>) inputKeys, target, failoverCount);
   }

   protected <T, K> DistributedTaskPart<T> createDistributedTaskPart(DistributedTask<T> task,
            DistributedExecuteCommand<T> c, Address target, int failoverCount) {
      return createDistributedTaskPart(task, c, Collections.emptyList(), target, failoverCount);
   }


   private <T, K> void checkExecutionPolicy(DistributedTask<T> task,
            Map<Address, List<K>> nodesKeysMap, K... input) {
      if (nodesKeysMap == null || nodesKeysMap.isEmpty()) {
         throw new IllegalStateException("DistributedTaskExecutionPolicy "
                  + task.getTaskExecutionPolicy() + " for task " + task
                  + " returned invalid keysToExecutionNodes " + nodesKeysMap
                  + " execution policy plan for a given input " + Arrays.toString(input));
      }
   }

   private <K> boolean inputKeysSpecified(K...input){
      return input != null && input.length > 0;
   }

   protected Address selectExecutionNode(List<Address> candidates) {
      List<Address> list = randomClusterMembers(candidates,1);
      return list.get(0);
   }

   protected <T> Address selectExecutionNode(DistributedTask<T> task) {
      return selectExecutionNode(executionCandidates(task));
   }

   protected List<Address> randomClusterMembers(final List<Address> members, int numNeeded) {
      if(members == null || members.isEmpty())
         throw new IllegalArgumentException("Invalid member list " + members);

      if (members.size() < numNeeded) {
         log.cannotSelectRandomMembers(numNeeded, members);
         numNeeded = members.size();
      }
      List<Address> membersCopy = new ArrayList<>(members);
      List<Address> chosen = new ArrayList<>(numNeeded);
      Random r = new Random();
      while (!membersCopy.isEmpty() && numNeeded >= chosen.size()) {
         int count = membersCopy.size();
         Address address = membersCopy.remove(r.nextInt(count));
         chosen.add(address);
      }
      return chosen;
   }

   protected <K> Map<Address, List<K>> keysToExecutionNodes(DistributedTaskExecutionPolicy policy, K... input) {
      DistributionManager dm = cache.getDistributionManager();
      Map<Address, List<K>> addressToKey = new HashMap<>(input.length * 2);
      boolean usingREPLMode = dm == null;
      for (K key : input) {
         Address ownerOfKey;
         if (usingREPLMode) {
            List<Address> members = new ArrayList<>(getMembers());
            members =  filterMembers(policy, members);
            // using REPL mode https://issues.jboss.org/browse/ISPN-1886
            // since keys and values are on all nodes, lets just pick randomly
            Collections.shuffle(members);
            ownerOfKey = members.get(0);
         } else {
            // DIST mode
            DistributionInfo distributionInfo = dm.getCacheTopology().getDistribution(key);
            Collection<Address> owners = distributionInfo.writeOwners();
            List<Address> filtered = filterMembers(policy, owners);
            if(!filtered.isEmpty()){
               ownerOfKey = filtered.get(0);
            } else {
               ownerOfKey = distributionInfo.primary();
            }
         }
         List<K> keysAtNode = addressToKey.get(ownerOfKey);
         if (keysAtNode == null) {
            keysAtNode = new LinkedList<>();
            addressToKey.put(ownerOfKey, keysAtNode);
         }
         keysAtNode.add(key);
      }
      return addressToKey;
   }

   private List<Address> filterMembers(DistributedTaskExecutionPolicy policy, Collection<Address> members) {
      NodeFilter filter;
      switch (policy) {
         case SAME_MACHINE:
            filter = SAME_MACHINE_FILTER;
            break;
         case SAME_SITE:
            filter = SAME_SITE_FILTER;
            break;
         case SAME_RACK:
            filter = SAME_RACK_FILTER;
            break;
         case ALL:
            filter = ALL_FILTER;
            break;
         default:
            filter = ALL_FILTER;
            break;
      }
      List<Address> result = new ArrayList<>();
      for (Address address : members) {
         if(address instanceof TopologyAwareAddress){
            TopologyAwareAddress taa = (TopologyAwareAddress)address;
            if(filter.include(taa, (TopologyAwareAddress)getAddress())){
               result.add(address);
            }
         } else {
            result.add(address);
         }
      }
      return result;
   }

   private void ensureAccessPermissions(final AdvancedCache<?, ?> cache) {
      AuthorizationManager authorizationManager = SecurityActions.getCacheAuthorizationManager(cache);
      if (authorizationManager != null) {
         authorizationManager.checkPermission(AuthorizationPermission.EXEC);
      }
   }

   private void ensureProperCacheState(AdvancedCache<?, ?> cache) throws NullPointerException,
            IllegalStateException {

      // We allow for INITIALIZING state so the ExecutorService can be used by components defining a method with
      // {@link Start} annotation
      if (cache.getStatus() != ComponentStatus.RUNNING && cache.getStatus() != ComponentStatus.INITIALIZING)
         throw new IllegalStateException("Invalid cache state " + cache.getStatus());
   }

   private void ensureFullCache(AdvancedCache<?, ?> cache) {
      List<AsyncInterceptor> interceptors = SecurityActions.getInterceptorChain(cache);
      if (interceptors == null || interceptors.isEmpty()) {
         throw log.distributedExecutorsNotSupported();
      }
   }

   private static class RandomNodeTaskFailoverPolicy implements DistributedTaskFailoverPolicy {

      public RandomNodeTaskFailoverPolicy() {
         super();
      }

      @Override
      public Address failover(FailoverContext fc) {
         return randomNode(fc.executionCandidates(),fc.executionFailureLocation());
      }

      protected Address randomNode(List<Address> candidates, Address failedExecutionLocation){
         Random r = new Random();
         candidates.remove(failedExecutionLocation);
         if (candidates.isEmpty())
            throw new IllegalStateException("There are no candidates for failover: " + candidates);
         int tIndex = r.nextInt(candidates.size());
         return candidates.get(tIndex);
      }

      @Override
      public int maxFailoverAttempts() {
         return 1;
      }
   }

   private static class NoTaskFailoverPolicy implements DistributedTaskFailoverPolicy {

      public NoTaskFailoverPolicy() {
         super();
      }

      @Override
      public Address failover(FailoverContext fc) {
         return fc.executionFailureLocation();
      }

      @Override
      public int maxFailoverAttempts() {
         return 0;
      }
   }

   /**
    * NodeFilter allows selection of nodes according to {@link DistributedTaskExecutionPolicy}
    */
   interface NodeFilter {
      boolean include(TopologyAwareAddress thisAddress, TopologyAwareAddress otherAddress);
   }

   private class DefaultDistributedTaskBuilder<T> implements DistributedTaskBuilder<T>, DistributedTask<T> {

      private Callable<T> callable;
      private long timeout;
      private DistributedTaskExecutionPolicy executionPolicy = DistributedTaskExecutionPolicy.ALL;
      private DistributedTaskFailoverPolicy failoverPolicy = NO_FAILOVER;


      public DefaultDistributedTaskBuilder(long taskTimeout) {
         this.timeout = taskTimeout;
      }

      @Override
      public DistributedTaskBuilder<T> callable(Callable<T> callable) {
         if (callable == null)
            throw new IllegalArgumentException("Callable cannot be null");
         this.callable = callable;
         return this;
      }

      @Override
      public DistributedTaskBuilder<T> timeout(long t, TimeUnit tu) {
         timeout = TimeUnit.MILLISECONDS.convert(t, tu);
         return this;
      }

      @Override
      public DistributedTaskBuilder<T> executionPolicy(DistributedTaskExecutionPolicy policy) {
         if (policy == null)
            throw new IllegalArgumentException("DistributedTaskExecutionPolicy cannot be null");

         this.executionPolicy = policy;
         return this;
      }

      @Override
      public DistributedTaskBuilder<T> failoverPolicy(DistributedTaskFailoverPolicy policy) {
         if (policy == null) {
            this.failoverPolicy = NO_FAILOVER;
         } else {
            this.failoverPolicy = policy;
         }
         return this;
      }

      @Override
      public DistributedTask<T> build() {
         DefaultDistributedTaskBuilder<T> task = new DefaultDistributedTaskBuilder<>(timeout);
         task.callable(callable);
         task.executionPolicy(executionPolicy);
         task.failoverPolicy(failoverPolicy);
         return task;
      }

      @Override
      public long timeout() {
         return timeout;
      }

      @Override
      public DistributedTaskExecutionPolicy getTaskExecutionPolicy() {
         return executionPolicy;
      }

      @Override
      public DistributedTaskFailoverPolicy getTaskFailoverPolicy() {
         return failoverPolicy;
      }

      @Override
      public Callable<T> getCallable() {
         return callable;
      }
   }

   /**
    * DistributedTaskPart represents a unit of work sent to remote VM and executed there
    *
    *
    * @author Mircea Markus
    * @author Vladimir Blagojevic
    */
   private abstract class DistributedTaskPart<V> extends CompletableFuture<V> implements RunnableFuture<V> {

      protected final DistributedExecuteCommand<V> distCommand;
      private final List<Object> inputKeys;
      private final DistributedTask<V> owningTask;
      private int failedOverCount;
      private volatile boolean cancelled;

      protected DistributedTaskPart(List<Object> inputKeys, DistributedExecuteCommand<V> command, DistributedTask<V> task, int failedOverCount) {
         this.inputKeys = inputKeys;
         this.distCommand = command;
         this.owningTask = task;
         this.failedOverCount = failedOverCount;
      }

      public List<Object> getInputKeys() {
         return inputKeys;
      }

      public DistributedExecuteCommand<V> getCommand() {
         return distCommand;
      }

      public DistributedTask<V> getOwningTask() {
         return owningTask;
      }

      public abstract Address getExecutionTarget();

      public abstract void execute();

      @Override
      public V get() throws InterruptedException, ExecutionException {
         try {
            return innerGet(0, TimeUnit.MILLISECONDS);
         } catch (TimeoutException e) {
            throw new ExecutionException(e);
         }
      }

      @Override
      public V get(long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
         return innerGet(timeout, unit);
      }

      protected V innerGet(long timeout, TimeUnit unit)
            throws ExecutionException, TimeoutException, InterruptedException {
         if (isCancelled())
            throw new CancellationException("Task already cancelled");

         long timeoutNanos = computeTimeoutNanos(timeout, unit);
         try {
            return getResult(timeoutNanos);
         } catch (TimeoutException te) {
            throw te;
         } catch (Exception e) {
            // The RPC could have finished with a org.infinispan.util.concurrent.TimeoutException right before
            // the Future.get timeout expired. If that's the case, we want to throw a TimeoutException.
            if (e instanceof ExecutionException && e.getCause() instanceof org.infinispan.util.concurrent.TimeoutException) {
               if (trace) log.tracef(
                     "Distributed task timed out, throwing a TimeoutException and ignoring exception", e);
               throw new TimeoutException();
            }
            boolean canFailover = failedOverCount++ < getOwningTask().getTaskFailoverPolicy().maxFailoverAttempts();
            if (canFailover) {
               try {
                  return failoverExecution(e, timeoutNanos, TimeUnit.NANOSECONDS);
               } catch (Exception failedOver) {
                  throw wrapIntoExecutionException(failedOver);
               }
            } else {
               throw wrapIntoExecutionException(e);
            }
         }
      }

      protected V getResult(long timeoutNanos) throws Exception {
         if (timeoutNanos > 0) {
            return super.get(timeoutNanos, TimeUnit.NANOSECONDS);
         } else {
            return super.get();
         }
      }

      protected long computeTimeoutNanos(long timeout, TimeUnit unit) {
         long taskTimeout = TimeUnit.MILLISECONDS.toNanos(getOwningTask().timeout());
         long futureTimeout = TimeUnit.NANOSECONDS.convert(timeout, unit);
         long actualTimeout;
         if (taskTimeout > 0 && futureTimeout > 0) {
            actualTimeout = Math.min(taskTimeout, futureTimeout);
         } else {
            actualTimeout = Math.max(taskTimeout, futureTimeout);
         }
         return actualTimeout;
      }

      protected ExecutionException wrapIntoExecutionException(Exception e){
         if (e instanceof ExecutionException) {
            return (ExecutionException) e;
         } else {
            return new ExecutionException(e);
         }
      }

      protected V failoverExecution(final Exception cause, long timeout, TimeUnit unit)
            throws Exception {
         final List<Address> executionCandidates = executionCandidates(getOwningTask());
         FailoverContext fc = new FailoverContext() {
            @Override
            public <K> List<K> inputKeys() {
               return (List<K>) getInputKeys();
            }

            @Override
            public Address executionFailureLocation() {
               return getExecutionTarget();
            }

            @Override
            public List<Address> executionCandidates() {
               return executionCandidates;
            }

            @Override
            public Throwable cause() {
               return cause;
            }
         };

         Address failoverTarget = getOwningTask().getTaskFailoverPolicy().failover(fc);
         log.distributedTaskFailover(fc.executionFailureLocation(), failoverTarget, cause);
         DistributedTaskPart<V> part = createDistributedTaskPart(owningTask, distCommand,
               getInputKeys(), failoverTarget, failedOverCount);
         part.execute();
         return part.get(timeout, unit);
      }

      @Override
      public void run() {
         execute();
      }
   }

   private class RemoteDistributedTaskPart<V> extends DistributedTaskPart<V> {
      private final Address executionTarget;

      public RemoteDistributedTaskPart(DistributedTask<V> task, DistributedExecuteCommand<V> command,
                                 List<Object> inputKeys, Address executionTarget, int failoverCount) {
         super(inputKeys, command, task, failoverCount);
         if (getAddress().equals(executionTarget)) {
            throw new IllegalArgumentException("This task should be executed as local.");
         }
         this.executionTarget = executionTarget;
      }

      @Override
      public Address getExecutionTarget() {
         return executionTarget;
      }

      @Override
      public void execute() {
         if (trace) log.tracef("Sending %s to remote execution at node %s", this, getExecutionTarget());
         try {
            rpc.invokeCommand(getExecutionTarget(), getCommand(), SingleResponseCollector.validOnly(),
                              new RpcOptions(DeliverOrder.NONE, getOwningTask().timeout(), TimeUnit.MILLISECONDS))
               .whenComplete((Response r, Throwable t) -> {
               if (t != null) {
                  completeExceptionally(t);
               } else {
                  try {
                     complete(retrieveResult(r));
                  } catch (Exception e) {
                     completeExceptionally(e);
                  }
               }
            });
         } catch (Throwable e) {
            log.remoteExecutionFailed(getExecutionTarget(), e);
         }
      }

      @Override
      public boolean cancel(boolean mayInterruptIfRunning) {
         if (!isCancelled() && super.cancel(true)) {
            CancelCommand ccc = factory.buildCancelCommandCommand(distCommand.getUUID());
            rpc.invokeRemotely(Collections.singletonList(getExecutionTarget()), ccc, rpc.getDefaultRpcOptions(true));
            return true;
         } else {
            //already cancelled
            return false;
         }
      }

      private V retrieveResult(Response response) throws Exception {
         if (response instanceof SuccessfulResponse) {
            return (V) ((SuccessfulResponse) response).getResponseValue();
         } else {
            throw new ExecutionException(new Exception(response != null ? response.toString() : "Unknown cause"));
         }
      }
   }

   private class LocalDistributedTaskPart<V> extends DistributedTaskPart<V> {
      public LocalDistributedTaskPart(DistributedTask<V> task, DistributedExecuteCommand<V> command,
               List<Object> inputKeys, int failoverCount) {
         super(inputKeys, command, task, failoverCount);
      }

      @Override
      public synchronized boolean cancel(boolean mayInterruptIfRunning) {
         if (!isCancelled() && super.cancel(true)) {
            CancelCommand ccc = factory.buildCancelCommandCommand(distCommand.getUUID());
            ccc.init(cancellationService);
            try {
               ccc.invoke();
            } catch (Throwable t) {
               log.couldNotExecuteCancellationLocally(t);
            }
            return true;
         } else {
            //already cancelled
            return false;
         }
      }

      @Override
      public Address getExecutionTarget() {
         return getAddress();
      }

      @Override
      public void execute() {
         log.debugf("Sending %s to self", this);
         try {
            Runnable call = () -> {
               getCommand().init(cache);
               DistributedTaskLifecycleService lifecycle = DistributedTaskLifecycleService.getInstance();
               try {
                  // hook into lifecycle
                  lifecycle.onPreExecute(getCommand().getCallable(), cache, Collections.unmodifiableCollection(getInputKeys()));
                  cancellationService.register(Thread.currentThread(), getCommand().getUUID());
                  getCommand().invokeAsync().whenComplete((rv, t) -> {
                     if (t != null) {
                        completeExceptionally(t);
                     } else {
                        complete((V) rv);
                     }

                     // hook into lifecycle
                     lifecycle.onPostExecute(getCommand().getCallable());
                     cancellationService.unregister(getCommand().getUUID());
                  });
               } catch (Throwable t) {
                  completeExceptionally(t);

                  // hook into lifecycle
                  lifecycle.onPostExecute(getCommand().getCallable());
                  cancellationService.unregister(getCommand().getUUID());
               }
            };
            localExecutorService.execute(call);
         } catch (Throwable e1) {
            log.localExecutionFailed(e1);
         }
      }
   }

}
