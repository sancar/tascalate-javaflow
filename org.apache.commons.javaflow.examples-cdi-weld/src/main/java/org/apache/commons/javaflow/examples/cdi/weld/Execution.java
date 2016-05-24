package org.apache.commons.javaflow.examples.cdi.weld;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import org.apache.commons.javaflow.api.continuable;
import org.apache.commons.javaflow.examples.cdi.weld.annotations.LoggableMethod;
import org.jboss.weld.bean.proxy.ProxyObject;

@ApplicationScoped
public class Execution implements Runnable {

    @Inject
    TargetInterface target;
     
    @LoggableMethod
    public @continuable void run() {
    	System.out.println("Target is proxy? " + (target instanceof ProxyObject));
        String[] array = new String[]{"A", "B", "C"};
        for (int i = 0; i < array.length; i++) {
            System.out.println("Execution " + i);
            invokeDependent(array[i]);
        }
    }
    
    protected @continuable void invokeDependent(final String value) {
        target.execute(value);        
    }
}
