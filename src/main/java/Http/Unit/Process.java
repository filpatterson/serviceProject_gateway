package Http.Unit;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.HashMap;

public class Process {
    //  global counter of running processes
    private static long processesCounter = 0;

    private long id;

    //  name of function related to process
    private String processName;

    //  current status of process
    private String status;

    //  arguments of chosen process
    @JsonIgnore
    HashMap<String, String> processArguments;

    //  process constructor
    public Process(String processName) {
        id = processesCounter++;
        this.processName = processName;
        status = "created";
        processArguments = new HashMap<>();
    }

    //  getters and setters

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getProcessName() {
        return processName;
    }

    public void setProcessName(String processName) {
        this.processName = processName;
    }

    public HashMap<String, String> getProcessArguments() {
        return processArguments;
    }

    public void setProcessArguments(HashMap<String, String> processArguments) {
        this.processArguments = processArguments;
    }

    @Override
    public String toString() {
        return "Process{" +
                "id=" + id +
                ", processName='" + processName + '\'' +
                ", status='" + status + '\'' +
                ", processArguments=" + processArguments +
                '}';
    }
}
