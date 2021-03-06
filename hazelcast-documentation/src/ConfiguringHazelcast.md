
### Configuring Hazelcast
When you download and unzip `hazelcast-`*version*`.zip` you will see the `hazelcast.xml` in **/bin** folder. This is the configuration XML file for Hazelcast, a part of which is shown below.
![](images/HazelcastXML.jpg)
For most of the users, default configuration should be fine. If not, you can tailor this XML file according to your needs by adding/removing/modifying properties. Please refer to [Configuration Properties](#advanced-configuration-properties) for details.
Besides XML configuration, you can configure your cluster programmatically. Just instantiate a `Config` object and add/remove/modify properties. 


**Related Information**

Please refer to [Configuration](#configuration) chapter for more information.
