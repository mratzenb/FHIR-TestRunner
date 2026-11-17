<#-- The template of THIRD-PARTY-NOTICES.txt (license-maven-plugin:add-third-party in testrunner-app/pom.xml): the
     libraries of the executable jar, the text above them is for the reader of the jar or of a container image. -->
<#function licenseFormat licenses>
    <#assign result = ""/>
    <#list licenses as license>
        <#assign result = result + license/>
        <#if license_has_next><#assign result = result + " / "/></#if>
    </#list>
    <#return result>
</#function>
<#function sourceFolder p>
    <#return "https://repo1.maven.org/maven2/" + p.groupId?replace(".", "/") + "/" + p.artifactId + "/" + p.version + "/">
</#function>
THIRD-PARTY SOFTWARE IN THE FHIR TESTSCRIPT RUNNER

The runner is licensed under the Mozilla Public License 2.0 (the file LICENSE next to this one). Its executable jar and
its container images also deliver the libraries below.

* Each library is delivered unchanged, as a jar of its own, with the license and notice files it carries (the
  executable jar: BOOT-INF/lib; the container images: /app/lib).
* Source: the Maven Central folder named at "Source" holds the sources jar (<artifactId>-<version>-sources.jar) of
  almost all of them. The project page is named too. For the libraries under the Mozilla Public License 2.0 (Saxon-HE) and
  the Eclipse Public License 2.0 (Logback, Jakarta Annotations) this is the Source Code Form the licenses ask to be
  made available.
* Libraries that are available under more than one license are used under their Eclipse Public License 2.0 option:
  Logback (alternatively LGPL 2.1), Jakarta Annotations (alternatively GPL 2 with the Classpath Exception).
* The texts of the licenses are in the folder "licenses" next to this file: Apache-2.0.txt, EPL-2.0.txt and
  Unicode-3.0.txt; the Mozilla Public License 2.0 is the file LICENSE. The copyright notices of the authors of the
  libraries under the MIT and the BSD 3-Clause licenses are in their jars and on their project pages.

<#if dependencyMap?size == 0>
The executable jar has no third-party libraries.
<#else>
${dependencyMap?size} libraries:

    <#list dependencyMap as e>
        <#assign p = e.getKey()/>
        <#assign licenses = e.getValue()/>
${p.groupId}:${p.artifactId}:${p.version}
    Name:    ${p.name!p.artifactId}
    License: ${licenseFormat(licenses)}
    Project: ${p.url!"-"}
    Source:  ${sourceFolder(p)}

    </#list>
</#if>
