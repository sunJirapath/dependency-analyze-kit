import groovy.json.JsonSlurper

def report = new JsonSlurper().parse(new File(basedir, 'shared-probes/report.json'))
assert report.probeReports > 0 : report
for (def key : ['org.springframework.boot:spring-boot-starter-data-redis', 'org.apache.commons:commons-lang3']) {
    assert report.dependencies.find { it.dependency == key }.verdict == 'UNUSED_CANDIDATE' : report
}
assert new File(basedir, 'build.log').text.contains('2 unused dependency candidate(s)')
return true
