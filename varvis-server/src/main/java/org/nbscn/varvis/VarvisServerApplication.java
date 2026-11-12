package org.nbscn.varvis;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class VarvisServerApplication {
    // 最大堆内存可通过 JVM 参数设置，例如 -Xmx20480m。
    // -Xms1024m -Xmx20480m
    public static void main(String[] args) {
        SpringApplication.run(VarvisServerApplication.class, args);
    }

}
