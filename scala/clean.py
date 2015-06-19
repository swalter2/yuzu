import os
print os.getcwd()
f_out = open("all_new.nt","w")

for line in open("all.nt","r"):
    line = line.replace("file://"+os.getcwd()+"/","")
    f_out.write(line)

f_out.close()


