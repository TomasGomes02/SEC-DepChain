## Highly Dependable Systems

### About

This project was developed alongside [@MartinSilveira](https://github.com/MartinSilveira/) and [@tomasmatos6](https://github.com/tomasmatos6/) for the Highly Dependable Systems course of the Instituto Superior Técnico Master's Degree on Computer Science and Engineering. We achieved a final grade of 19.13 out of 20.

The goal was to create a simplified permissioned Blockchain system with high dependability guarantees, called Dependable Chain (DepChain). The first stage of the project focused on building the consensus layer, while the second stage focused on the transaction processing layer.

The full report on the development of DepChain can be found on [REPORT.pdf](https://github.com/TomasGomes02/SEC-DepChain/blob/main/REPORT.pdf) in this same repository!

### Tests
Starting off, if the file all_members.p12 is not present in the root directory, please run ThreshSighSetup.java in order to create this file. This will setup all the keys and signatures.

We provide the option to use tests to verify the functioning of the program. These can be ran all at once using the command "mvn clean install" or ran individually with "mvn test -Dtest=". (if any tests fail when running them all at once, please try that test again individually)

### Manually running clients and members

Alternatively, the application can be manually tested. In order to start the client, run Client.java. Then, to start the 4 members, run MemberService.java. They should automatically initialize the handshakes and estabilish a connection between the peers and the client. Then, on the client side, you can login with the command "login ", where id can be 10, 11, 12 or 13. After that, feel free to play around with the commands we provide! Use "help" for all the commands available.

You can set the Byzantine Behaviour of the members in MemberService.java in these lines:
```
if (i == 1) {
     members.add(new BlockchainConsensus(i, addr, new DatagramSocket(8000 + i), secrets, ByzantineBehaviour.NONE));
}
```

where 1 is the id of the member to attribute the Byzantine Behaviour to. You can choose any id between 1 and 4. To change the behaviour, replace the NONE with any of the following: { WRONG_COMMAND, NO_RESPONSE, CRASH, RANDOM_MESSAGE, OVERSIZED_BLOCK, BAD_SORTING }

To set the Byzantine Behaviour of the client, you can initialize it by running Client.java, then logging in to one of the clients. Finally, you can use the command "behaviour ", where mode can be either Replay or Signature.
