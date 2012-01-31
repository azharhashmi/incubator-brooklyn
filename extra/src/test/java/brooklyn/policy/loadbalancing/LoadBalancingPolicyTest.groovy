package brooklyn.policy.loadbalancing

import static brooklyn.test.TestUtils.*
import static java.util.concurrent.TimeUnit.*
import static org.testng.Assert.*

import java.util.Map
import java.util.Set

import org.testng.annotations.BeforeMethod
import org.testng.annotations.Test

import brooklyn.entity.Application;
import brooklyn.entity.ConfigKey
import brooklyn.entity.Entity
import brooklyn.entity.Group
import brooklyn.entity.basic.AbstractEntity
import brooklyn.entity.basic.AbstractGroup
import brooklyn.entity.basic.DynamicGroup
import brooklyn.event.AttributeSensor
import brooklyn.event.SensorEvent
import brooklyn.event.SensorEventListener
import brooklyn.event.basic.BasicAttributeSensor
import brooklyn.event.basic.BasicConfigKey
import brooklyn.location.basic.SimulatedLocation
import brooklyn.test.entity.TestApplication

public class LoadBalancingPolicyTest {
    
    public static final AttributeSensor<Integer> TEST_METRIC =
        new BasicAttributeSensor<Integer>(Integer.class, "test.metric", "Dummy workrate for test entities")
    
    public static final ConfigKey<Double> LOW_THRESHOLD_CONFIG_KEY = new BasicConfigKey<Double>(Double.class, "metric.threshold.low", "desc", 0.0)
    public static final ConfigKey<Double> HIGH_THRESHOLD_CONFIG_KEY = new BasicConfigKey<Double>(Double.class, "metric.threshold.high", "desc", 0.0)
    
    private TestApplication app
    private SimulatedLocation loc
    private BalanceableWorkerPool pool
    private LoadBalancingPolicy policy
    private Group containerGroup
    
    
    @BeforeMethod()
    public void before() {
        app = new TestApplication()
        pool = new BalanceableWorkerPool([:], app)
        containerGroup = new DynamicGroup([name:"containerGroup"], app, { e -> (e instanceof MockContainerEntity) })
        pool.setContents(containerGroup)
        
        policy = new LoadBalancingPolicy([:], TEST_METRIC, new DefaultBalanceablePoolModel<Entity, Entity>("pool-model") {
            @Override public void moveItem(Entity item, Entity oldNode, Entity newNode) {
                super.moveItem(item, oldNode, newNode)
                ((MockItemEntity) item).move(newNode)
            }
        })
        policy.setEntity(pool)
        
        app.start([loc])
    }
    
    // Expect no balancing to occur as container A isn't above the high threshold.
    @Test
    public void testNoopBalancing() {
        // Set-up containers and items.
        MockContainerEntity containerA = newContainer(app, "A", 10, 100)
        MockContainerEntity containerB = newContainer(app, "B", 20, 60)
        MockItemEntity item1 = newItem(app, containerA, "1", 10)
        MockItemEntity item2 = newItem(app, containerA, "2", 10)
        MockItemEntity item3 = newItem(app, containerA, "3", 10)
        MockItemEntity item4 = newItem(app, containerA, "4", 10)
        
        executeUntilSucceeds(timeout:5000) {
            assertEquals(getContainerWorkrate(containerA), 40d)
            assertEquals(getContainerWorkrate(containerB), 0d)
        }
    }
    
    // Expect 20 units of workload to be migrated from hot container (A) to cold (B).
    @Test
    public void testSimpleBalancing() {
        // Set-up containers and items.
        MockContainerEntity containerA = newContainer(app, "A", 10, 25)
        MockContainerEntity containerB = newContainer(app, "B", 20, 60)
        MockItemEntity item1 = newItem(app, containerA, "1", 10)
        MockItemEntity item2 = newItem(app, containerA, "2", 10)
        MockItemEntity item3 = newItem(app, containerA, "3", 10)
        MockItemEntity item4 = newItem(app, containerA, "4", 10)
        
        executeUntilSucceeds(timeout:5000) {
            assertEquals(getContainerWorkrate(containerA), 20d)
            assertEquals(getContainerWorkrate(containerB), 20d)
        }
    }
    
    // Expect no balancing to occur in hot pool (2 containers over-threshold at 40).
    // On addition of new container, expect hot containers to offload 10 each.
    @Test
    public void testAddContainerWhenHot() {
        // Set-up containers and items.
        MockContainerEntity containerA = newContainer(app, "A", 10, 30)
        MockContainerEntity containerB = newContainer(app, "B", 10, 30)
        MockItemEntity item1 = newItem(app, containerA, "1", 10)
        MockItemEntity item2 = newItem(app, containerA, "2", 10)
        MockItemEntity item3 = newItem(app, containerA, "3", 10)
        MockItemEntity item4 = newItem(app, containerA, "4", 10)
        MockItemEntity item5 = newItem(app, containerB, "5", 10)
        MockItemEntity item6 = newItem(app, containerB, "6", 10)
        MockItemEntity item7 = newItem(app, containerB, "7", 10)
        MockItemEntity item8 = newItem(app, containerB, "8", 10)
        // Both containers are over-threshold at this point; should not rebalance.
        
        MockContainerEntity containerC = newContainer(app, "C", 10, 30)
        // New container allows hot ones to offload work.
        
        executeUntilSucceeds(timeout:5000) {
            assertEquals(getContainerWorkrate(containerA), 30d)
            assertEquals(getContainerWorkrate(containerB), 30d)
            assertEquals(getContainerWorkrate(containerC), 20d)
        }
    }
    
    // On addition of new container, expect no rebalancing to occur as no existing container is hot.
    @Test
    public void testAddContainerWhenCold() {
        // Set-up containers and items.
        MockContainerEntity containerA = newContainer(app, "A", 10, 50)
        MockContainerEntity containerB = newContainer(app, "B", 10, 50)
        MockItemEntity item1 = newItem(app, containerA, "1", 10)
        MockItemEntity item2 = newItem(app, containerA, "2", 10)
        MockItemEntity item3 = newItem(app, containerA, "3", 10)
        MockItemEntity item4 = newItem(app, containerA, "4", 10)
        MockItemEntity item5 = newItem(app, containerB, "5", 10)
        MockItemEntity item6 = newItem(app, containerB, "6", 10)
        MockItemEntity item7 = newItem(app, containerB, "7", 10)
        MockItemEntity item8 = newItem(app, containerB, "8", 10)
        
        executeUntilSucceeds(timeout:5000) {
            assertEquals(getContainerWorkrate(containerA), 40d)
            assertEquals(getContainerWorkrate(containerB), 40d)
        }
        
        MockContainerEntity containerC = newContainer(app, "C", 10, 50)
        
        executeUntilSucceeds(timeout:5000) {
            assertEquals(getContainerWorkrate(containerA), 40d)
            assertEquals(getContainerWorkrate(containerB), 40d)
            assertEquals(getContainerWorkrate(containerC), 0d)
        }
    }
    
    // Expect no balancing to occur in cool pool (2 containers under-threshold at 30).
    // On addition of new item, expect over-threshold container (A) to offload 20 to B.
    @Test
    public void testAddItem() {
        // Set-up containers and items.
        MockContainerEntity containerA = newContainer(app, "A", 10, 50)
        MockContainerEntity containerB = newContainer(app, "B", 10, 50)
        MockItemEntity item1 = newItem(app, containerA, "1", 10)
        MockItemEntity item2 = newItem(app, containerA, "2", 10)
        MockItemEntity item3 = newItem(app, containerA, "3", 10)
        MockItemEntity item4 = newItem(app, containerB, "4", 10)
        MockItemEntity item5 = newItem(app, containerB, "5", 10)
        MockItemEntity item6 = newItem(app, containerB, "6", 10)
        
        executeUntilSucceeds(timeout:5000) {
            assertEquals(getContainerWorkrate(containerA), 30d)
            assertEquals(getContainerWorkrate(containerB), 30d)
        }
        
        MockItemEntity item7 = newItem(app, containerA, "7", 40)
        
        executeUntilSucceeds(timeout:5000) {
            assertEquals(getContainerWorkrate(containerA), 50d)
            assertEquals(getContainerWorkrate(containerB), 50d)
        }
    }
    
    
    // Testing conveniences.
     
    private static MockContainerEntity newContainer(Application app, String name, double lowThreshold, double highThreshold) {
        // Annoyingly, can't set owner until after the threshold config has been defined.
        MockContainerEntity container = new MockContainerEntity([displayName:name])
        container.setConfig(LOW_THRESHOLD_CONFIG_KEY, lowThreshold)
        container.setConfig(HIGH_THRESHOLD_CONFIG_KEY, highThreshold)
        container.setOwner(app)
        app.getManagementContext().manage(container)
        return container
    }
    
    private static MockItemEntity newItem(Application app, MockContainerEntity container, String name, double workrate) {
        MockItemEntity item = new MockItemEntity([displayName:name], app)
        app.getManagementContext().manage(item)
        item.move(container)
        item.setAttribute(TEST_METRIC, workrate)
        return item
    }
    
    private static double getItemWorkrate(MockItemEntity item) {
        Object result = item.getAttribute(TEST_METRIC)
        return (result == null ? 0 : ((Number) result).doubleValue())
    }
    
    private static double getContainerWorkrate(MockContainerEntity container) {
        double result = 0.0
        container.getBalanceableItems().each { MockItemEntity item ->
            assertEquals(item.getContainerId(), container.getId())
            result += getItemWorkrate(item)
        }
        return result
    }
    
}
