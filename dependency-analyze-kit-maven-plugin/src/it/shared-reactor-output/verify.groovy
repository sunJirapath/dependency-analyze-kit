import groovy.json.JsonSlurper

def base = new File(basedir, 'shared-probes')
def a = new JsonSlurper().parse(new File(base, 'io.github.sunjirapath.it/app-a/report.json'))
def b = new JsonSlurper().parse(new File(base, 'io.github.sunjirapath.it/app-b/report.json'))
assert a.probeReports > 0 : a
assert b.probeReports == 0 : b
assert b.dependencies.find { it.dependency == 'org.springframework.boot:spring-boot-starter-jdbc' }.verdict == 'UNKNOWN' : b
def probes = base.listFiles().findAll { it.name.startsWith('context-') && it.name.endsWith('.json') }
assert !probes.empty
assert probes.every { new JsonSlurper().parse(it).projectDirectory == new File(basedir, 'app-a').toPath().toRealPath().toString() }
def log = new File(basedir, 'build.log').text
assert log.contains('Skipping probe report with missing or foreign module identity')
assert log.contains('Incomplete dependency analysis: current full-context probe evidence required')
return true
